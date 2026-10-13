package com.botcraft.gametest;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.entity.CompanionPlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.Direction;
import com.botcraft.nav.Goal;
import com.botcraft.nav.PathFinder;
import com.botcraft.nav.Terrain;
import com.botcraft.util.TickBudget;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Headless tests, run with ./gradlew runGametest. Each test builds its own floor inside the
 * empty Fabric template, so no structure files are needed.
 */
public class CompanionGameTests implements FabricGameTest {
	/** Remove animals left over from other tests nearby, so a test only sees its own. */
	static void clearAnimals(TestContext ctx) {
		var center = ctx.getAbsolute(new Vec3d(0, 0, 0));
		for (var a : ctx.getWorld().getEntitiesByClass(net.minecraft.entity.passive.AnimalEntity.class,
			new net.minecraft.util.math.Box(center, center).expand(96, 32, 96), e -> true)) {
			a.discard();
		}
	}

	static void floor(TestContext ctx, int size) {
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
			}
		}
	}

	static CompanionManager manager(TestContext ctx) {
		CompanionManager m = CompanionManager.get();
		if (m == null) {
			ctx.throwGameTestException("CompanionManager not running");
		}
		// Many tests run side by side, each with its own companions.
		com.botcraft.config.CompanionConfig.get().maxCompanions = Math.max(64, com.botcraft.config.CompanionConfig.get().maxCompanions);
		com.botcraft.config.CompanionConfig.get().initiative = 0; // tests want only the behaviour under test
		com.botcraft.config.CompanionConfig.get().placeTorches = true; // (a failed test may have left it off)
		// No shared base unless the test sets one (base tests run in batches of their own).
		var colony = com.botcraft.colony.Colony.get();
		if (colony != null) {
			colony.clear();
		}
		com.botcraft.brain.Board.clear();
		return m;
	}

	/** Milestone 1: spawn joins the player list, physics run (falls onto the floor), remove leaves. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 120)
	public void spawnFallAndRemove(TestContext ctx) {
		floor(ctx, 8);
		CompanionManager m = manager(ctx);
		Vec3d spawn = ctx.getAbsolute(new Vec3d(3.5, 4.0, 3.5));
		String error = m.spawn("GtSpawn", "kai", null, ctx.getWorld(), spawn, 0.0F);
		ctx.assertTrue(error == null, "spawn failed: " + error);
		Companion c = m.byName("GtSpawn");
		CompanionPlayer p = c.player();
		ctx.assertTrue(p != null, "no player entity");
		ctx.assertTrue(ctx.getWorld().getServer().getPlayerManager().getPlayer(p.getUuid()) == p, "not in player list");
		ctx.waitAndRun(40, () -> {
			BlockPos feet = p.getBlockPos();
			ctx.assertTrue(p.isOnGround(), "companion should have landed, at " + p.getPos());
			ctx.assertTrue(feet.getY() == ctx.getAbsolutePos(new BlockPos(0, 1, 0)).getY(), "landed at wrong height " + p.getPos());
			ctx.assertTrue(m.remove("GtSpawn"), "remove failed");
			ctx.assertTrue(ctx.getWorld().getServer().getPlayerManager().getPlayer(p.getUuid()) == null, "still in player list");
			ctx.complete();
		});
	}

	/** Remove leftovers from a previous failed run. */
	static void cleanup(CompanionManager m, String... names) {
		for (String n : names) {
			m.remove(n);
		}
	}

	/**
	 * Closed corridor (x 0..14, z 0..4): a raised 2-block section to jump onto and drop off,
	 * then a wall at x=8 whose only opening is a closed oak door. Floor at y=0.
	 */
	static void buildCourse(TestContext ctx) {
		for (int x = -1; x <= 15; x++) {
			for (int z = -1; z <= 5; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 4; y++) {
					boolean wall = x == -1 || x == 15 || z == -1 || z == 5;
					ctx.setBlockState(x, y, z, wall && y <= 3 ? Blocks.STONE_BRICKS : Blocks.AIR);
				}
			}
		}
		for (int z = 0; z <= 4; z++) {
			ctx.setBlockState(4, 1, z, Blocks.STONE);
			ctx.setBlockState(5, 1, z, Blocks.STONE);
			ctx.setBlockState(8, 1, z, Blocks.STONE_BRICKS);
			ctx.setBlockState(8, 2, z, Blocks.STONE_BRICKS);
			ctx.setBlockState(8, 3, z, Blocks.STONE_BRICKS);
		}
		BlockState door = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.EAST);
		ctx.setBlockState(8, 1, 2, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		ctx.setBlockState(8, 2, 2, door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	/** Milestone 2 (planner only): A* finds a route over the step and through the door. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "nav_astar")
	public void astarThroughDoor(TestContext ctx) {
		buildCourse(ctx);
		Terrain terrain = new Terrain(ctx.getWorld());
		BlockPos start = ctx.getAbsolutePos(new BlockPos(1, 1, 2));
		BlockPos end = ctx.getAbsolutePos(new BlockPos(10, 1, 2));
		PathFinder pf = new PathFinder(terrain, start, Goal.near(end, 0.5), PathFinder.Options.basic(5000, 64), new LongOpenHashSet());
		TickBudget.begin(1000);
		PathFinder.Status st = pf.step(100000);
		ctx.assertTrue(st == PathFinder.Status.FOUND, "expected FOUND, got " + st + " after " + pf.expanded() + " nodes");
		boolean jumped = pf.result().stream().anyMatch(s -> s.type() == com.botcraft.nav.MoveType.JUMP);
		boolean door = pf.result().stream().anyMatch(s -> s.pos().equals(ctx.getAbsolutePos(new BlockPos(8, 1, 2))));
		ctx.assertTrue(jumped, "path should jump onto the step");
		ctx.assertTrue(door, "path should go through the door");
		ctx.complete();
	}

	/** Cells another companion stands in or is about to walk through: the path steps round them. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "nav_busy")
	public void pathStepsRoundOthers(TestContext ctx) {
		flatArena(ctx, 10);
		Terrain terrain = new Terrain(ctx.getWorld());
		BlockPos start = ctx.getAbsolutePos(new BlockPos(5, 1, 0));
		BlockPos end = ctx.getAbsolutePos(new BlockPos(5, 1, 9));
		LongOpenHashSet busy = new LongOpenHashSet();
		for (int z = 3; z <= 6; z++) {
			busy.add(ctx.getAbsolutePos(new BlockPos(5, 1, z)).asLong());
		}
		PathFinder pf = new PathFinder(terrain, start, Goal.near(end, 0.5), PathFinder.Options.basic(5000, 64), new LongOpenHashSet()).busy(busy);
		TickBudget.begin(1000);
		ctx.assertTrue(pf.step(100000) == PathFinder.Status.FOUND, "no path");
		ctx.assertTrue(pf.result().stream().noneMatch(st -> busy.contains(st.pos().asLong())), "walked through someone: " + pf.result().stream()
			.map(st -> st.pos().toShortString()).toList());
		ctx.complete();
	}

	/**
	 * A forest with leaves at head height whose canopy steps up like an oak's: the path stays on
	 * the forest floor (breaking leaves), not up over the treetops.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "nav_forest")
	public void forestFloorNotCanopy(TestContext ctx) {
		for (int x = -1; x <= 22; x++) {
			for (int z = -1; z <= 5; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				boolean wall = x == -1 || x == 22 || z == -1 || z == 5;
				for (int y = 1; y <= 7; y++) {
					ctx.setBlockState(x, y, z, wall ? Blocks.STONE : Blocks.AIR);
				}
			}
		}
		for (int z = 0; z <= 4; z++) {
			ctx.setBlockState(3, 1, z, Blocks.OAK_LEAVES); // a low bush: the step up onto the canopy
		}
		for (int x = 4; x <= 17; x++) {
			for (int z = 0; z <= 4; z++) {
				ctx.setBlockState(x, 2, z, Blocks.OAK_LEAVES);
				if (x >= 6 && x <= 15) {
					ctx.setBlockState(x, 3, z, Blocks.OAK_LEAVES);
				}
				if (x >= 8 && x <= 13) {
					ctx.setBlockState(x, 4, z, Blocks.OAK_LEAVES);
				}
			}
		}
		for (int x : new int[]{6, 10, 14}) {
			for (int y = 1; y <= 4; y++) {
				ctx.setBlockState(x, y, 2, Blocks.OAK_LOG);
			}
		}
		Terrain terrain = new Terrain(ctx.getWorld());
		BlockPos start = ctx.getAbsolutePos(new BlockPos(1, 1, 2));
		BlockPos end = ctx.getAbsolutePos(new BlockPos(20, 1, 2));
		PathFinder pf = new PathFinder(terrain, start, Goal.near(end, 0.5), new PathFinder.Options(true, false, true, 0, 20000, 64, 3),
			new LongOpenHashSet());
		TickBudget.begin(1000);
		ctx.assertTrue(pf.step(100000) == PathFinder.Status.FOUND, "no path through the forest");
		ctx.assertTrue(pf.result().stream().allMatch(st -> st.pos().getY() == start.getY()), "went up into the trees: " + pf.result().stream()
			.map(st -> st.pos().toShortString()).toList());
		ctx.complete();
	}

	/** Diagonal past one blocked corner (Baritone's edging): one step, not two. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "nav_edging")
	public void diagonalEdgesRoundACorner(TestContext ctx) {
		flatArena(ctx, 6);
		ctx.setBlockState(2, 1, 2, Blocks.STONE);
		ctx.setBlockState(2, 2, 2, Blocks.STONE);
		Terrain terrain = new Terrain(ctx.getWorld());
		BlockPos start = ctx.getAbsolutePos(new BlockPos(2, 1, 1));
		BlockPos end = ctx.getAbsolutePos(new BlockPos(3, 1, 2));
		PathFinder pf = new PathFinder(terrain, start, Goal.near(end, 0.5), PathFinder.Options.basic(5000, 64), new LongOpenHashSet());
		TickBudget.begin(1000);
		ctx.assertTrue(pf.step(100000) == PathFinder.Status.FOUND, "no path");
		ctx.assertTrue(pf.result().size() == 1, "expected one diagonal step, got " + pf.result().stream().map(st -> st.pos().toShortString()).toList());
		ctx.complete();
	}

	/** Replanning after the goal moved keeps to the side of an obstacle the old path went round. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "nav_favor")
	public void replanKeepsToThePreviousRoute(TestContext ctx) {
		flatArena(ctx, 11);
		for (int z = 3; z <= 7; z++) {
			ctx.setBlockState(5, 1, z, Blocks.STONE);
			ctx.setBlockState(5, 2, z, Blocks.STONE);
		}
		LongOpenHashSet favored = new LongOpenHashSet();
		for (int x = 0; x <= 10; x++) {
			for (int z = 6; z <= 10; z++) {
				favored.add(ctx.getAbsolutePos(new BlockPos(x, 1, z)).asLong());
			}
		}
		Terrain terrain = new Terrain(ctx.getWorld());
		BlockPos start = ctx.getAbsolutePos(new BlockPos(2, 1, 5));
		BlockPos end = ctx.getAbsolutePos(new BlockPos(8, 1, 5));
		PathFinder pf = new PathFinder(terrain, start, Goal.near(end, 0.5), PathFinder.Options.basic(5000, 64), new LongOpenHashSet()).favor(favored);
		TickBudget.begin(1000);
		ctx.assertTrue(pf.step(100000) == PathFinder.Status.FOUND, "no path");
		int minZ = ctx.getAbsolutePos(new BlockPos(0, 1, 5)).getZ();
		ctx.assertTrue(pf.result().stream().allMatch(st -> st.pos().getZ() >= minZ), "went round the other side: " + pf.result().stream()
			.map(st -> st.pos().toShortString()).toList());
		ctx.complete();
	}

	/** Under leaves is outdoors (no escape mode, no torches), under stone is not. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "nav_leafsky")
	public void underLeavesIsOutdoors(TestContext ctx) {
		flatArena(ctx, 6);
		ctx.setBlockState(1, 4, 1, Blocks.OAK_LEAVES);
		ctx.setBlockState(4, 4, 4, Blocks.STONE);
		ctx.assertTrue(Terrain.openSky(ctx.getWorld(), ctx.getAbsolutePos(new BlockPos(1, 2, 1))), "under leaves counted as underground");
		ctx.assertTrue(!Terrain.openSky(ctx.getWorld(), ctx.getAbsolutePos(new BlockPos(4, 2, 4))), "under stone counted as open sky");
		ctx.complete();
	}

	/** Right-click inventory of a companion: chest layout, live contents, take and give, armour slots only take armour. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100, batchId = "inventory_view")
	public void inventoryViewTakesAndGives(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtLooker", "GtLooked");
		flatArena(ctx, 6);
		ctx.assertTrue(m.spawn("GtLooker", "steve", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 1.5)), 0.0F) == null, "viewer spawn");
		ctx.assertTrue(m.spawn("GtLooked", "alex", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 1.5)), 0.0F) == null, "target spawn");
		Companion looker = m.byName("GtLooker");
		Companion looked = m.byName("GtLooked");
		com.botcraft.chat.CommandTasks.stay(looker);
		com.botcraft.chat.CommandTasks.stay(looked);
		var inv = looked.player().getInventory();
		var mine = looker.player().getInventory();
		inv.clear();
		mine.clear();
		inv.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD));
		inv.setStack(9, new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 12));
		inv.setStack(39, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_HELMET));
		mine.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_BOOTS));
		// Companions don't open each other's inventories through right-clicks.
		ctx.assertTrue(com.botcraft.entity.InventoryView.onUseEntity(looker.player(), ctx.getWorld(), net.minecraft.util.Hand.MAIN_HAND, looked.player(), null)
			== net.minecraft.util.ActionResult.PASS, "a companion opened another's inventory");
		com.botcraft.entity.InventoryView.open(looker.player(), looked.player());
		ctx.assertTrue(looker.player().currentScreenHandler instanceof com.botcraft.entity.InventoryView.Handler, "no inventory screen open");
		var h = looker.player().currentScreenHandler;
		var pickup = net.minecraft.screen.slot.SlotActionType.PICKUP;
		var shift = net.minecraft.screen.slot.SlotActionType.QUICK_MOVE;
		ctx.assertTrue(h.getSlot(0).getStack().isOf(net.minecraft.item.Items.COBBLESTONE), "main inventory not in row 1");
		ctx.assertTrue(h.getSlot(27).getStack().isOf(net.minecraft.item.Items.DIAMOND_SWORD), "hotbar not in row 4");
		ctx.assertTrue(h.getSlot(36).getStack().isOf(net.minecraft.item.Items.IRON_HELMET), "helmet not in row 5");
		// Take: shift-click the cobblestone over to the viewer.
		h.onSlotClick(0, 0, shift, looker.player());
		ctx.assertTrue(inv.getStack(9).isEmpty() && mine.count(net.minecraft.item.Items.COBBLESTONE) == 12, "shift-click didn't take the cobblestone");
		// Give: shift-click boots from the viewer's hotbar (slot 45 + 27) onto the companion's feet.
		h.onSlotClick(45 + 27, 0, shift, looker.player());
		ctx.assertTrue(inv.getStack(36).isOf(net.minecraft.item.Items.DIAMOND_BOOTS), "boots not worn: " + inv.getStack(36));
		// Move: pick the sword up and put it down in the main inventory.
		h.onSlotClick(27, 0, pickup, looker.player());
		h.onSlotClick(5, 0, pickup, looker.player());
		ctx.assertTrue(inv.getStack(0).isEmpty() && inv.getStack(14).isOf(net.minecraft.item.Items.DIAMOND_SWORD), "sword not moved");
		// Armour slots take only armour that fits; filler slots take nothing.
		var dirt = new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIRT);
		ctx.assertTrue(!h.getSlot(37).canInsert(dirt) && h.getSlot(37).canInsert(new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE))
			&& !h.getSlot(37).canInsert(new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_BOOTS)), "chest slot takes the wrong things");
		ctx.assertTrue(h.getSlot(40).canInsert(dirt) && !h.getSlot(41).canInsert(dirt), "off hand or filler slot wrong");
		inv.setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, 3));
		ctx.assertTrue(h.getSlot(28).getStack().isOf(net.minecraft.item.Items.BREAD), "view doesn't follow the inventory");
		looker.player().closeHandledScreen();
		cleanup(m, "GtLooker", "GtLooked");
		ctx.complete();
	}

	/** Milestone 2 (end to end): a companion follows its owner over the step and through a closed door. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "nav_follow")
	public void followThroughDoor(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtOwner", "GtFollow");
		buildCourse(ctx);
		ctx.assertTrue(m.spawn("GtOwner", "steve", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(13.5, 1, 2.5)), 90.0F) == null, "owner spawn");
		ctx.assertTrue(m.spawn("GtFollow", "alex", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 2.5)), -90.0F) == null, "follower spawn");
		Companion owner = m.byName("GtOwner");
		Companion follower = m.byName("GtFollow");
		com.botcraft.chat.CommandTasks.stay(owner);
		follower.data.owner = owner.player().getUuid();
		follower.data.ownerName = "GtOwner";
		com.botcraft.chat.CommandTasks.follow(follower, null);
		ctx.addInstantFinalTask(() -> {
			CompanionPlayer f = follower.player();
			double d = f.distanceTo(owner.player());
			ctx.assertTrue(f.getX() > ctx.getAbsolute(new Vec3d(8.9, 0, 0)).x, "not through the door yet (at " + f.getPos() + ", age " + f.age + ", nav " + follower.nav().state() + " " + follower.nav().failReason() + ", door " + ctx.getWorld().getBlockState(ctx.getAbsolutePos(new BlockPos(8, 1, 2))) + ")");
			ctx.assertTrue(d < 4.5, "too far from owner: " + d);
			cleanup(m, "GtOwner", "GtFollow");
		});
	}

	/** Milestone 3: gather 3 logs from a small natural tree (bare hands). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "gather")
	public void gatherLogs(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtLumber");
		for (int x = -2; x <= 10; x++) {
			for (int z = -2; z <= 8; z++) {
				ctx.setBlockState(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y <= 9; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		// Trunk of 4 logs at (6, 1..4, 3) with a leaf canopy (tree-grown leaves are non-persistent by default).
		for (int y = 1; y <= 4; y++) {
			ctx.setBlockState(6, y, 3, Blocks.OAK_LOG);
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int y = 4; y <= 5; y++) {
					if ((dx != 0 || dz != 0) && Math.abs(dx) + Math.abs(dz) < 4) {
						ctx.setBlockState(6 + dx, y, 3 + dz, Blocks.OAK_LEAVES);
					}
				}
			}
		}
		ctx.setBlockState(6, 5, 3, Blocks.OAK_LEAVES);
		ctx.assertTrue(m.spawn("GtLumber", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 3.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtLumber");
		com.botcraft.chat.CommandTasks.stay(c);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.gather(c, "logs", 3, null) == null, "gather order");
		ctx.addInstantFinalTask(() -> {
			int logs = com.botcraft.action.Inventories.count(c.player().getInventory(), com.botcraft.action.Inventories::isLog);
			ctx.assertTrue(logs >= 3, "has " + logs + " logs; " + c.describeActivity() + ", job " + c.data.job);
			cleanup(m, "GtLumber");
		});
	}

	static void flatArena(TestContext ctx, int size) {
		for (int x = -1; x <= size; x++) {
			for (int z = -1; z <= size; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 4; y++) {
					boolean wall = x == -1 || z == -1 || x == size || z == size;
					ctx.setBlockState(x, y, z, wall && y <= 2 ? Blocks.STONE_BRICKS : Blocks.AIR);
				}
			}
		}
	}

	/** Milestone 4: a hungry companion eats bread from its inventory. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300, batchId = "survive_eat")
	public void eatsWhenHungry(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtHungry");
		flatArena(ctx, 6);
		ctx.assertTrue(m.spawn("GtHungry", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtHungry");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, 5));
		c.player().getHungerManager().setFoodLevel(5);
		ctx.addInstantFinalTask(() -> {
			int food = c.player().getHungerManager().getFoodLevel();
			ctx.assertTrue(food >= 14, "food level " + food + "; " + c.describeActivity());
			cleanup(m, "GtHungry");
		});
	}

	/** Milestone 4/6: even a timid companion fights when armed: picks its best weapon and kills a zombie. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "survive_fight")
	public void fightsZombie(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtFighter");
		flatArena(ctx, 10);
		ctx.assertTrue(m.spawn("GtFighter", "zuri", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 5.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtFighter");
		c.data.bravery = 0.0F;
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().setStack(5, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_SWORD));
		net.minecraft.entity.mob.ZombieEntity zombie = ctx.spawnEntity(net.minecraft.entity.EntityType.ZOMBIE, new BlockPos(8, 1, 5));
		zombie.setPersistent();
		// A helmet keeps it from burning in daylight, so only the companion can kill it.
		zombie.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.LEATHER_HELMET));
		ctx.addInstantFinalTask(() -> {
			int kills = c.player().getStatHandler().getStat(net.minecraft.stat.Stats.KILLED.getOrCreateStat(net.minecraft.entity.EntityType.ZOMBIE));
			ctx.assertTrue(kills > 0 && !zombie.isAlive(), "zombie still alive (hp " + zombie.getHealth() + "); companion " + c.describeActivity()
				+ ", holding " + c.player().getMainHandStack() + ", difficulty " + ctx.getWorld().getDifficulty());
			ctx.assertTrue(c.player().isAlive(), "companion died");
			cleanup(m, "GtFighter");
		});
	}

	/** Milestone 4: companions run from creepers. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300, batchId = "survive_flee")
	public void fleesCreeper(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtCoward");
		flatArena(ctx, 16);
		ctx.assertTrue(m.spawn("GtCoward", "efe", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 8.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtCoward");
		com.botcraft.chat.CommandTasks.stay(c);
		net.minecraft.entity.mob.CreeperEntity creeper = ctx.spawnEntity(net.minecraft.entity.EntityType.CREEPER, new BlockPos(2, 1, 8));
		creeper.setAiDisabled(true);
		creeper.setPersistent();
		ctx.addInstantFinalTask(() -> {
			double d = c.player().distanceTo(creeper);
			ctx.assertTrue(d > 7.5, "only " + d + " from creeper; " + c.describeActivity());
			creeper.discard();
			cleanup(m, "GtCoward");
		});
	}

	/** Milestone 5: a chat order is parsed, obeyed and acknowledged; low health triggers a remark. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300, batchId = "chat")
	public void chatOrdersAndEvents(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtTalker", "GtBoss");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtBoss", "sunny", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(6.5, 1, 4.5)), 90.0F) == null, "boss spawn");
		ctx.assertTrue(m.spawn("GtTalker", "makena", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 4.5)), -90.0F) == null, "talker spawn");
		Companion boss = m.byName("GtBoss");
		Companion talker = m.byName("GtTalker");
		com.botcraft.chat.CommandTasks.stay(boss);
		com.botcraft.chat.CommandTasks.stay(talker);
		talker.data.owner = boss.player().getUuid();
		talker.data.ownerName = "GtBoss";
		talker.data.chattiness = 1.0F;
		var parsed = com.botcraft.chat.IntentParser.parse("GtTalker, follow me!", java.util.List.of("GtTalker", "GtBoss"));
		ctx.assertTrue(parsed.addressed().equals(java.util.List.of("GtTalker")), "addressed " + parsed.addressed());
		talker.hear(boss.player(), parsed.intent());
		ctx.runAtTick(120, () -> talker.player().setHealth(4.0F));
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue("follow".equals(talker.data.mode), "mode " + talker.data.mode);
			ctx.assertTrue(talker.speech().spokenKeys().contains("ack.follow"), "said " + talker.speech().spokenKeys());
			ctx.assertTrue(talker.speech().spokenKeys().contains("event.low_health"), "said " + talker.speech().spokenKeys());
			cleanup(m, "GtTalker", "GtBoss");
		});
	}

	/** Milestone 6: armed companions fight creepers too (this one can't fuse: AI disabled). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "armed_creeper")
	public void armedFightsCreeper(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtBrave");
		flatArena(ctx, 10);
		ctx.assertTrue(m.spawn("GtBrave", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 5.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtBrave");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().setStack(20, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_SWORD));
		net.minecraft.entity.mob.CreeperEntity creeper = ctx.spawnEntity(net.minecraft.entity.EntityType.CREEPER, new BlockPos(7, 1, 5));
		creeper.setAiDisabled(true);
		creeper.setPersistent();
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(!creeper.isAlive(), "creeper alive; " + c.describeActivity());
			cleanup(m, "GtBrave");
		});
	}

	/** Milestone 6: better armour in the inventory gets worn. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100, batchId = "armor")
	public void wearsArmor(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtKnight");
		flatArena(ctx, 4);
		ctx.assertTrue(m.spawn("GtKnight", "efe", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtKnight");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().setStack(12, new net.minecraft.item.ItemStack(net.minecraft.item.Items.LEATHER_CHESTPLATE));
		c.player().getInventory().setStack(13, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE));
		c.player().getInventory().setStack(14, new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_HELMET));
		ctx.addInstantFinalTask(() -> {
			var p = c.player();
			ctx.assertTrue(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isOf(net.minecraft.item.Items.IRON_CHESTPLATE), "chest " + p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST));
			ctx.assertTrue(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isOf(net.minecraft.item.Items.GOLDEN_HELMET), "head");
			cleanup(m, "GtKnight");
		});
	}

	static int count(Companion c, net.minecraft.item.Item item) {
		return com.botcraft.action.Inventories.count(c.player().getInventory(), s -> s.isOf(item));
	}

	/** Milestone 8: craft a wooden pickaxe from 3 logs: planks, sticks, a crafting table placed and picked back up. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 800, batchId = "craft")
	public void craftsPickaxeFromLogs(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtCrafter");
		flatArena(ctx, 7);
		ctx.assertTrue(m.spawn("GtCrafter", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtCrafter");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.BIRCH_LOG, 3));
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "craft a wooden pickaxe", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.WOODEN_PICKAXE) == 1, "no pickaxe; " + c.describeActivity() + "; inv " + c.player().getInventory().main);
			ctx.assertTrue(count(c, net.minecraft.item.Items.CRAFTING_TABLE) == 1, "table not picked back up");
			ctx.assertTrue(c.jobs().isIdle(), "still busy: " + c.describeActivity());
			cleanup(m, "GtCrafter");
		});
	}

	/** Milestone 8: told to mine stone without a pickaxe: crafts one from its logs first, then mines. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "mine_stone")
	public void minesStoneAfterMakingPickaxe(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtMiner");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtMiner", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtMiner");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_LOG, 4));
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "mine 3 stone", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.COBBLESTONE) >= 3, "cobblestone " + count(c, net.minecraft.item.Items.COBBLESTONE)
				+ "; " + c.describeActivity() + "; queued " + c.jobs().pending());
			ctx.assertTrue(count(c, net.minecraft.item.Items.WOODEN_PICKAXE) == 1, "made a pickaxe");
			cleanup(m, "GtMiner");
		});
	}

	/** Milestone 7/8: a chained chat order tunnels through natural stone to coal hidden in a wall. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200, batchId = "mine_coal")
	public void tunnelsToCoal(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtDigger");
		flatArena(ctx, 12);
		for (int x = 6; x <= 11; x++) {
			for (int z = 0; z <= 11; z++) {
				for (int y = 1; y <= 4; y++) {
					ctx.setBlockState(x, y, z, Blocks.STONE);
				}
			}
		}
		ctx.setBlockState(9, 1, 5, Blocks.COAL_ORE);
		ctx.assertTrue(m.spawn("GtDigger", "sunny", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 5.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtDigger");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE));
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.SPRUCE_LOG));
		var parsed = com.botcraft.chat.IntentParser.parse("GtDigger, mine 1 coal and then craft a torch", java.util.List.of("GtDigger"));
		ctx.assertTrue(parsed.intents().size() == 2, "intents " + parsed.intents());
		ctx.assertTrue(com.botcraft.chat.ChatRouter.respond(c, null, parsed.intents(), false) == null, "respond");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.TORCH) >= 1, "no torch; coal " + count(c, net.minecraft.item.Items.COAL)
				+ "; " + c.describeActivity() + "; queued " + c.jobs().pending());
			cleanup(m, "GtDigger");
		});
	}

	/** Your request: a started tree is felled completely, pillaring up on dug dirt for the high logs. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 4000, batchId = "tall_tree")
	public void fellsWholeTallTree(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtFeller");
		for (int x = -2; x <= 12; x++) {
			for (int z = -3; z <= 9; z++) {
				ctx.setBlockState(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y <= 14; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		for (int y = 1; y <= 9; y++) {
			ctx.setBlockState(6, y, 3, Blocks.OAK_LOG);
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int y = 8; y <= 10; y++) {
					if ((dx != 0 || dz != 0 || y == 10) && Math.abs(dx) + Math.abs(dz) < 4) {
						ctx.setBlockState(6 + dx, y, 3 + dz, Blocks.OAK_LEAVES);
					}
				}
			}
		}
		ctx.assertTrue(m.spawn("GtFeller", "steve", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 3.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtFeller");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		ctx.assertTrue(com.botcraft.chat.CommandTasks.gather(c, "logs", 2, null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			int left = 0;
			for (int y = 1; y <= 9; y++) {
				if (ctx.getWorld().getBlockState(ctx.getAbsolutePos(new BlockPos(6, y, 3))).isOf(Blocks.OAK_LOG)) {
					left++;
				}
			}
			ctx.assertTrue(left == 0, left + " logs still standing; " + c.describeActivity());
			ctx.assertTrue(c.jobs().isIdle(), "still busy: " + c.describeActivity());
			int logs = count(c, net.minecraft.item.Items.OAK_LOG);
			ctx.assertTrue(logs >= 7, "only " + logs + " logs collected");
			cleanup(m, "GtFeller");
		});
	}

	/** Solid box of {@code block} (x,z 0..8, y 0..4) with open air above; returns nothing. */
	static void solidGround(TestContext ctx, net.minecraft.block.Block block) {
		for (int x = -1; x <= 9; x++) {
			for (int z = -1; z <= 9; z++) {
				for (int y = 0; y <= 4; y++) {
					ctx.setBlockState(x, y, z, block);
				}
				for (int y = 5; y <= 9; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
	}

	static Companion[] ownerAndFollower(TestContext ctx, String owner, String follower, Vec3d ownerAt, Vec3d followerAt) {
		CompanionManager m = manager(ctx);
		cleanup(m, owner, follower);
		ctx.assertTrue(m.spawn(owner, "steve", null, ctx.getWorld(), ctx.getAbsolute(ownerAt), 0.0F) == null, "owner spawn");
		ctx.assertTrue(m.spawn(follower, "alex", null, ctx.getWorld(), ctx.getAbsolute(followerAt), 0.0F) == null, "follower spawn");
		Companion o = m.byName(owner);
		Companion f = m.byName(follower);
		com.botcraft.chat.CommandTasks.stay(o);
		f.player().getInventory().clear();
		f.data.owner = o.player().getUuid();
		f.data.ownerName = owner;
		com.botcraft.chat.CommandTasks.follow(f, null);
		return new Companion[]{o, f};
	}

	static void expectNear(TestContext ctx, Companion f, Companion o, double dist, String... cleanupNames) {
		ctx.addInstantFinalTask(() -> {
			double d = f.player().distanceTo(o.player());
			ctx.assertTrue(d < dist && Math.abs(f.player().getY() - o.player().getY()) < 1.5,
				"follower at " + f.player().getPos() + ", " + String.format("%.1f", d) + " from owner; " + f.describeActivity());
			cleanup(manager(ctx), cleanupNames);
		});
	}

	/** Nav v2: dug into a stone shaft with no blocks: escape mode digs a staircase out. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "nav_escape_dig")
	public void escapesShaftByDigging(TestContext ctx) {
		solidGround(ctx, Blocks.STONE);
		for (int y = 1; y <= 4; y++) {
			ctx.setBlockState(2, y, 4, Blocks.AIR);
		}
		Companion[] c = ownerAndFollower(ctx, "GtTop", "GtPit", new Vec3d(7.5, 5, 4.5), new Vec3d(2.5, 1, 4.5));
		c[1].player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE));
		expectNear(ctx, c[1], c[0], 4.5, "GtTop", "GtPit");
	}

	/** Nav v2: a shaft of bricks (not natural, can't dig) but dirt in the pocket: pillar out. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "nav_escape_pillar")
	public void escapesShaftByPillaring(TestContext ctx) {
		solidGround(ctx, Blocks.STONE_BRICKS);
		for (int y = 1; y <= 4; y++) {
			ctx.setBlockState(2, y, 4, Blocks.AIR);
		}
		Companion[] c = ownerAndFollower(ctx, "GtTop2", "GtPit2", new Vec3d(6.5, 5, 4.5), new Vec3d(2.5, 1, 4.5));
		c[1].player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIRT, 16));
		expectNear(ctx, c[1], c[0], 4.5, "GtTop2", "GtPit2");
	}

	/** Nav v2: climbs a ladder up a wall to reach its owner. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "nav_ladder")
	public void climbsLadder(TestContext ctx) {
		flatArena(ctx, 10);
		for (int x = 0; x <= 9; x++) {
			for (int z = 0; z <= 9; z++) {
				for (int y = 1; y <= 4; y++) {
					ctx.setBlockState(x, y, z, x >= 5 ? Blocks.STONE_BRICKS : Blocks.AIR);
				}
			}
		}
		for (int y = 1; y <= 4; y++) {
			ctx.setBlockState(4, y, 5, Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, Direction.WEST));
		}
		Companion[] c = ownerAndFollower(ctx, "GtRoof", "GtClimber", new Vec3d(7.5, 5, 5.5), new Vec3d(1.5, 1, 5.5));
		expectNear(ctx, c[1], c[0], 4.0, "GtRoof", "GtClimber");
	}

	/** Nav v2: sprint-jumps a 2-block gap between platforms (the pit is too deep to climb out of). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "nav_parkour")
	public void jumpsGap(TestContext ctx) {
		for (int x = -1; x <= 12; x++) {
			for (int z = 2; z <= 6; z++) {
				for (int y = 0; y <= 9; y++) {
					boolean platform = y == 8 && (x <= 4 || x >= 7);
					ctx.setBlockState(x, y, z, platform ? Blocks.STONE_BRICKS : Blocks.AIR);
				}
			}
		}
		Companion[] c = ownerAndFollower(ctx, "GtFar", "GtJumper", new Vec3d(10.5, 9, 4.5), new Vec3d(1.5, 9, 4.5));
		expectNear(ctx, c[1], c[0], 4.0, "GtFar", "GtJumper");
	}

	static void give(Companion c, net.minecraft.item.Item item, int n) {
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(item, n));
	}

	/** Mechanics: "craft an iron pickaxe" from raw iron: crafts + places a furnace, smelts, then crafts. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2600, batchId = "smelt_iron")
	public void smeltsIronForPickaxe(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtSmith");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtSmith", "efe", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtSmith");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.RAW_IRON, 3);
		give(c, net.minecraft.item.Items.OAK_LOG, 2);
		give(c, net.minecraft.item.Items.COBBLESTONE, 8);
		give(c, net.minecraft.item.Items.COAL, 2);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "craft an iron pickaxe", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_PICKAXE) == 1, "no iron pickaxe; " + c.describeActivity()
				+ "; queued " + c.jobs().pending() + "; inv " + c.player().getInventory().main.stream().filter(st -> !st.isEmpty()).toList());
			cleanup(m, "GtSmith");
		});
	}

	/** Mechanics: hunt a cow (only when there are more than two) and cook the beef. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2000, batchId = "hunt_cook")
	public void huntsAndCooks(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtHunter");
		flatArena(ctx, 12);
		for (int i = 0; i < 4; i++) {
			ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(8 + (i % 2), 1, 4 + i * 2)).setPersistent();
		}
		ctx.assertTrue(m.spawn("GtHunter", "zuri", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 6.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtHunter");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		give(c, net.minecraft.item.Items.FURNACE, 1);
		give(c, net.minecraft.item.Items.COAL, 1);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "kill 1 cow and then cook the beef", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.COOKED_BEEF) >= 1, "no cooked beef; " + c.describeActivity()
				+ "; queued " + c.jobs().pending() + "; inv " + c.player().getInventory().main.stream().filter(st -> !st.isEmpty()).toList());
			cleanup(m, "GtHunter");
		});
	}

	static void grassArena(TestContext ctx, int size) {
		for (int x = -1; x <= size; x++) {
			for (int z = -1; z <= size; z++) {
				ctx.setBlockState(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y <= 4; y++) {
					boolean wall = x == -1 || z == -1 || x == size || z == size;
					ctx.setBlockState(x, y, z, wall && y <= 2 ? Blocks.STONE_BRICKS : Blocks.AIR);
				}
			}
		}
	}

	/** Autonomy: told "do your own thing" with nothing, it decides to chop wood and make itself a sword. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 3000, batchId = "free_mode")
	public void freeModeArmsItself(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtFree");
		grassArena(ctx, 12);
		for (int y = 1; y <= 4; y++) {
			ctx.setBlockState(8, y, 6, Blocks.OAK_LOG);
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int y = 4; y <= 5; y++) {
					if ((dx != 0 || dz != 0) && Math.abs(dx) + Math.abs(dz) < 4) {
						ctx.setBlockState(8 + dx, y, 6 + dz, Blocks.OAK_LEAVES);
					}
				}
			}
		}
		ctx.assertTrue(m.spawn("GtFree", "sunny", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 6.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtFree");
		c.player().getInventory().clear();
		c.data.initiative = 1.0F;
		c.data.curiosity = 0.0F; // no exploring off the test area
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "do your own thing", null) == null, "order");
		ctx.assertTrue("free".equals(c.data.mode), "mode " + c.data.mode);
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.WOODEN_SWORD) >= 1, "no sword; " + c.describeActivity()
				+ "; inv " + c.player().getInventory().main.stream().filter(st -> !st.isEmpty()).toList());
			cleanup(m, "GtFree");
		});
	}

	/** Social: companions chat with each other; sharing an ore sighting teaches the listener where it is. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "social")
	public void companionsBanterAndShare(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtAnna", "GtBen");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtAnna", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 4.5)), -90.0F) == null, "spawn a");
		ctx.assertTrue(m.spawn("GtBen", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 4.5)), 90.0F) == null, "spawn b");
		Companion a = m.byName("GtAnna");
		Companion b = m.byName("GtBen");
		com.botcraft.chat.CommandTasks.stay(a);
		com.botcraft.chat.CommandTasks.stay(b);
		BlockPos iron = ctx.getAbsolutePos(new BlockPos(1, -20, 1));
		a.memory().sight("iron", iron, a.dimension(), 0);
		ctx.runAtTick(60, () -> com.botcraft.chat.Social.banter(a, b, "found"));
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(a.speech().spokenKeys().contains("banter.found"), "a said " + a.speech().spokenKeys());
			ctx.assertTrue(b.speech().spokenKeys().contains("banter.found.reply"), "b said " + b.speech().spokenKeys());
			ctx.assertTrue(!b.memory().sightings("iron", iron, a.dimension(), 2).isEmpty(), "b didn't learn the iron spot");
			cleanup(m, "GtAnna", "GtBen");
		});
	}

	/** Night without a weapon or bed: digs three blocks down and roofs itself in. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "shelter")
	public void digsShelter(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtMole");
		for (int x = -1; x <= 6; x++) {
			for (int z = -1; z <= 6; z++) {
				for (int y = 0; y <= 3; y++) {
					ctx.setBlockState(x, y, z, y == 3 ? Blocks.GRASS_BLOCK : Blocks.DIRT);
				}
				for (int y = 4; y <= 8; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		ctx.assertTrue(m.spawn("GtMole", "efe", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 4, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtMole");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.brain().setJob(new com.botcraft.brain.task.ShelterTask(c, false).asJob("shelter"));
		int ground = ctx.getAbsolutePos(new BlockPos(0, 4, 0)).getY();
		ctx.addInstantFinalTask(() -> {
			BlockPos feet = com.botcraft.nav.Navigator.feet(c.player());
			ctx.assertTrue(feet.getY() <= ground - 3, "not dug in: feet " + feet.toShortString() + " ground " + ground);
			ctx.assertTrue(!ctx.getWorld().getBlockState(feet.up(2)).isAir(), "no roof over " + feet.toShortString());
			cleanup(m, "GtMole");
		});
	}

	/** Sharing: missing coal for torches, a companion asks an idle friend, who brings some over. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200, batchId = "share")
	public void friendSharesCoal(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtNeedy", "GtGiver");
		flatArena(ctx, 10);
		ctx.assertTrue(m.spawn("GtNeedy", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 5.5)), -90.0F) == null, "spawn a");
		ctx.assertTrue(m.spawn("GtGiver", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(8.5, 1, 5.5)), 90.0F) == null, "spawn b");
		Companion a = m.byName("GtNeedy");
		Companion b = m.byName("GtGiver");
		com.botcraft.chat.CommandTasks.stay(a);
		com.botcraft.chat.CommandTasks.stay(b);
		a.player().getInventory().clear();
		b.player().getInventory().clear();
		give(a, net.minecraft.item.Items.OAK_LOG, 2);
		give(b, net.minecraft.item.Items.COAL, 6);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(a, "craft 4 torches", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(a, net.minecraft.item.Items.TORCH) >= 4, "no torches; a: " + a.describeActivity() + "; b: " + b.describeActivity()
				+ "; b coal " + count(b, net.minecraft.item.Items.COAL) + "; a said " + a.speech().spokenKeys());
			ctx.assertTrue(count(b, net.minecraft.item.Items.COAL) < 6, "b didn't share");
			cleanup(m, "GtNeedy", "GtGiver");
		});
	}

	/** Sharing is only for things worth a trip: cobblestone is never requested. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "share_cheap")
	public void noAskingForCobblestone(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtCheapA", "GtCheapB");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtCheapA", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 4.5)), 0.0F) == null, "spawn a");
		ctx.assertTrue(m.spawn("GtCheapB", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 4.5)), 0.0F) == null, "spawn b");
		Companion a = m.byName("GtCheapA");
		Companion b = m.byName("GtCheapB");
		com.botcraft.chat.CommandTasks.stay(a);
		com.botcraft.chat.CommandTasks.stay(b);
		give(b, net.minecraft.item.Items.COBBLESTONE, 32);
		ctx.assertTrue(com.botcraft.brain.Sharing.ask(a, "stone", "cobblestone", 3, false) == null, "asked for cobblestone");
		ctx.assertTrue(com.botcraft.brain.Sharing.ask(a, "logs", "logs", 3, false) == null, "asked for logs");
		cleanup(m, "GtCheapA", "GtCheapB");
		ctx.complete();
	}

	/** "X, give Y your logs" to a companion standing on a ledge (no clean eye line): still delivered. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 800, batchId = "give_ledge")
	public void givesToFriendOnLedge(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtGiver2", "GtLedge");
		flatArena(ctx, 10);
		for (int x = 6; x <= 9; x++) {
			for (int z = 3; z <= 7; z++) {
				ctx.setBlockState(x, 1, z, Blocks.STONE_BRICKS);
				ctx.setBlockState(x, 2, z, Blocks.STONE_BRICKS);
			}
		}
		ctx.assertTrue(m.spawn("GtGiver2", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 5.5)), -90.0F) == null, "spawn a");
		ctx.assertTrue(m.spawn("GtLedge", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(7.5, 3, 5.5)), 90.0F) == null, "spawn b");
		Companion a = m.byName("GtGiver2");
		Companion b = m.byName("GtLedge");
		com.botcraft.chat.CommandTasks.stay(a);
		com.botcraft.chat.CommandTasks.stay(b);
		a.player().getInventory().clear();
		b.player().getInventory().clear();
		give(a, net.minecraft.item.Items.OAK_LOG, 6);
		var parsed = com.botcraft.chat.IntentParser.parse("GtGiver2, give GtLedge your logs", java.util.List.of("GtGiver2", "GtLedge"));
		ctx.assertTrue("GtLedge".equals(parsed.intent().target()), "target " + parsed.intent());
		ctx.assertTrue(com.botcraft.chat.ChatRouter.respond(a, null, parsed.intents(), false) == null, "respond");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(a, net.minecraft.item.Items.OAK_LOG) == 0, "a still has logs; " + a.describeActivity());
			cleanup(m, "GtGiver2", "GtLedge");
		});
	}

	/** Spreading out: a spot someone else has claimed (or is standing at) is avoided when there's another choice. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "claims")
	public void spreadsOutFromOthers(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtClaimA", "GtClaimB");
		floor(ctx, 16);
		ctx.assertTrue(m.spawn("GtClaimA", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 1.5)), 0.0F) == null, "spawn a");
		ctx.assertTrue(m.spawn("GtClaimB", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(14.5, 1, 14.5)), 0.0F) == null, "spawn b");
		Companion a = m.byName("GtClaimA");
		Companion b = m.byName("GtClaimB");
		com.botcraft.chat.CommandTasks.stay(a);
		com.botcraft.chat.CommandTasks.stay(b);
		BlockPos claimed = ctx.getAbsolutePos(new BlockPos(8, 1, 1));
		BlockPos byA = ctx.getAbsolutePos(new BlockPos(2, 1, 2));
		BlockPos free = ctx.getAbsolutePos(new BlockPos(8, 1, 14));
		com.botcraft.brain.Claims.claim(a, claimed);
		ctx.assertTrue(com.botcraft.brain.Claims.crowded(b, claimed, 5), "b should see a's claim");
		ctx.assertTrue(com.botcraft.brain.Claims.crowded(b, byA, 5), "b should avoid where a stands");
		ctx.assertTrue(!com.botcraft.brain.Claims.crowded(a, claimed, 5), "a's own claim is fine for a");
		var picks = com.botcraft.brain.Claims.spreadOut(b, java.util.List.of(claimed, byA, free), x -> x, 5);
		ctx.assertTrue(picks.equals(java.util.List.of(free)), "picks " + picks);
		var all = com.botcraft.brain.Claims.spreadOut(b, java.util.List.of(claimed), x -> x, 5);
		ctx.assertTrue(all.size() == 1, "nothing free: share rather than give up");
		com.botcraft.brain.Claims.release(a);
		ctx.assertTrue(!com.botcraft.brain.Claims.crowded(b, claimed, 5), "released");
		cleanup(m, "GtClaimA", "GtClaimB");
		ctx.complete();
	}

	/** Tool prep: easy upgrades from what's carried (raw iron gets smelted), never spending diamonds. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "tool_prep_plan")
	public void toolPrepPlansUpgrades(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtPrep");
		floor(ctx, 8);
		ctx.assertTrue(m.spawn("GtPrep", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtPrep");
		com.botcraft.chat.CommandTasks.stay(c);
		var inv = c.player().getInventory();
		inv.clear();
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.RAW_IRON, 3);
		give(c, net.minecraft.item.Items.COAL, 2);
		give(c, net.minecraft.item.Items.STICK, 2);
		give(c, net.minecraft.item.Items.FURNACE, 1);
		var prep = com.botcraft.brain.task.ToolPrep.before(c, "mine diamond 3");
		ctx.assertTrue(prep.equals(java.util.List.of("?smelt iron_ingot 3", "?craft iron_pickaxe 1")), "iron upgrade: " + prep);
		inv.clear();
		give(c, net.minecraft.item.Items.WOODEN_AXE, 1);
		give(c, net.minecraft.item.Items.DIAMOND, 3);
		give(c, net.minecraft.item.Items.COBBLESTONE, 3);
		give(c, net.minecraft.item.Items.STICK, 2);
		prep = com.botcraft.brain.task.ToolPrep.before(c, "gather logs 8");
		ctx.assertTrue(prep.equals(java.util.List.of("?craft stone_axe 1")), "stone, not diamond: " + prep);
		inv.clear();
		give(c, net.minecraft.item.Items.IRON_SWORD, 1);
		give(c, net.minecraft.item.Items.COBBLESTONE, 3);
		give(c, net.minecraft.item.Items.STICK, 2);
		prep = com.botcraft.brain.task.ToolPrep.before(c, "hunt cow 2");
		ctx.assertTrue(prep.isEmpty(), "already has a good sword: " + prep);
		ctx.assertTrue(com.botcraft.brain.task.ToolPrep.before(c, "goto 0 0 0 there").isEmpty(), "no tool for walking");
		cleanup(m, "GtPrep");
		ctx.complete();
	}

	/** Tool prep end to end: told to mine with a wooden pickaxe and cobblestone on hand, it upgrades first. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "tool_prep_run")
	public void upgradesPickaxeBeforeMining(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtUpgrade");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtUpgrade", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtUpgrade");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.WOODEN_PICKAXE, 1);
		give(c, net.minecraft.item.Items.COBBLESTONE, 3);
		give(c, net.minecraft.item.Items.STICK, 2);
		give(c, net.minecraft.item.Items.CRAFTING_TABLE, 1);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "mine 2 stone", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.STONE_PICKAXE) == 1, "no stone pickaxe; " + c.describeActivity()
				+ "; queued " + c.jobs().pending());
			ctx.assertTrue(count(c, net.minecraft.item.Items.COBBLESTONE) >= 2, "mined after upgrading; " + c.describeActivity());
			cleanup(m, "GtUpgrade");
		});
	}

	/** Hunting with nothing in sight: it picks a direction and goes looking instead of giving up. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 3600, batchId = "hunt_search")
	public void huntSearchesForAnimals(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtTracker");
		for (int x = -50; x <= 56; x++) {
			for (int z = -50; z <= 56; z++) {
				ctx.setBlockState(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y <= 4; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		int[][] herds = {{49, 3}, {-43, 3}, {3, 49}, {3, -43}};
		for (int[] h : herds) {
			for (int i = 0; i < 3; i++) {
				ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(h[0] + i, 1, h[1])).setPersistent();
			}
		}
		ctx.assertTrue(m.spawn("GtTracker", "zuri", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtTracker");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "hunt 1 cow", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.BEEF) >= 1 || count(c, net.minecraft.item.Items.LEATHER) >= 1,
				"found no cow; at " + c.player().getBlockPos() + "; " + c.describeActivity() + "; queued " + c.jobs().pending());
			cleanup(m, "GtTracker");
		});
	}

	/** Swimming down: a path that goes to the bottom of a deep pool dives instead of bobbing at the top. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "nav_dive")
	public void divesToPoolBottom(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtDiver");
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
				for (int y = 1; y <= 9; y++) {
					ctx.setBlockState(x, y, z, wall ? (y <= 7 ? Blocks.STONE : Blocks.AIR) : y <= 7 ? Blocks.WATER : Blocks.AIR);
				}
			}
		}
		ctx.assertTrue(m.spawn("GtDiver", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 7.5, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtDiver");
		com.botcraft.chat.CommandTasks.stay(c);
		BlockPos bottom = ctx.getAbsolutePos(new BlockPos(3, 1, 3));
		c.jobs().replace(java.util.List.of("goto " + bottom.getX() + " " + bottom.getY() + " " + bottom.getZ() + " the bottom"));
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(c.player().getY() <= bottom.getY() + 1.6, "still at y " + c.player().getY() + " (bottom " + bottom.getY() + "); "
				+ c.describeActivity());
			cleanup(m, "GtDiver");
		});
	}

	/** Drops from our own work get picked up (a log broken out of reach); someone else's thrown item doesn't. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "leftovers")
	public void picksUpOwnDrops(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtTidy", "GtThrower");
		flatArena(ctx, 12);
		ctx.setBlockState(9, 1, 9, Blocks.OAK_LOG);
		ctx.assertTrue(m.spawn("GtTidy", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		ctx.assertTrue(m.spawn("GtThrower", "ari", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(10.5, 1, 2.5)), 0.0F) == null, "spawn b");
		Companion c = m.byName("GtTidy");
		Companion other = m.byName("GtThrower");
		com.botcraft.chat.CommandTasks.stay(c);
		com.botcraft.chat.CommandTasks.stay(other);
		c.player().getInventory().clear();
		c.data.mode = "follow"; // no owner: free to tidy up
		// Something someone else threw, right by where our log will drop: not ours.
		var thrown = new net.minecraft.entity.ItemEntity(ctx.getWorld(), ctx.getAbsolute(new Vec3d(9.5, 1, 8.5)).x,
			ctx.getAbsolute(new Vec3d(9.5, 1, 8.5)).y, ctx.getAbsolute(new Vec3d(9.5, 1, 8.5)).z, new net.minecraft.item.ItemStack(net.minecraft.item.Items.APPLE));
		thrown.setThrower(other.player().getUuid());
		ctx.getWorld().spawnEntity(thrown);
		// Break the log the way a player does (the drop lands where the log was, out of pickup range).
		c.player().interactionManager.tryBreakBlock(ctx.getAbsolutePos(new BlockPos(9, 1, 9)));
		var lying = c.leftovers().lying();
		ctx.assertTrue(lying.size() == 1 && lying.get(0).getStack().isOf(net.minecraft.item.Items.OAK_LOG), "ours: " + lying);
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.OAK_LOG) == 1, "didn't collect its log; " + c.describeActivity()
				+ ", expected " + c.leftovers().anyExpected() + ", lying " + c.leftovers().lying());
			cleanup(m, "GtTidy", "GtThrower");
		});
	}

	/** Nobody to follow and nothing to do, far from home: it walks home instead of idling there. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "lost_home")
	public void lostCompanionGoesHome(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtLost");
		for (int x = -1; x <= 34; x++) {
			for (int z = -1; z <= 5; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 3; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		ctx.assertTrue(m.spawn("GtLost", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtLost");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		c.data.mode = "follow";
		BlockPos home = ctx.getAbsolutePos(new BlockPos(32, 1, 2));
		c.memory().setPlace("home", home, c.dimension(), ctx.getWorld().getTime());
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(c.player().getBlockPos().getSquaredDistance(home) < 4 * 4, "not home: at " + c.player().getBlockPos()
				+ " (home " + home + "); " + c.describeActivity());
			cleanup(m, "GtLost");
		});
	}

	/** Tool prep stays out of sub-steps: logs for a table the iron pickaxe needs don't become an iron axe. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "tool_prep_substep")
	public void toolPrepSkipsSubSteps(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtFrugal");
		floor(ctx, 8);
		ctx.assertTrue(m.spawn("GtFrugal", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtFrugal");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.IRON_INGOT, 3);
		give(c, net.minecraft.item.Items.STICK, 2);
		give(c, net.minecraft.item.Items.CRAFTING_TABLE, 1);
		ctx.assertTrue(com.botcraft.brain.task.ToolPrep.before(c, "gather logs 1").equals(java.util.List.of("?craft iron_axe 1")), "top-level job may upgrade");
		c.jobs().pushFront(java.util.List.of("craft iron_pickaxe 1 ~1"));
		ctx.assertTrue(com.botcraft.brain.task.ToolPrep.before(c, "gather logs 1").isEmpty(), "sub-step must not spend the pickaxe's iron");
		c.jobs().clear();
		give(c, net.minecraft.item.Items.OAK_PLANKS, 8);
		ctx.assertTrue(com.botcraft.brain.task.ToolPrep.before(c, "mine iron 3").equals(java.util.List.of("?craft iron_pickaxe 1")), "iron pick for iron");
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.OAK_PLANKS, 8);
		ctx.assertTrue(com.botcraft.brain.task.ToolPrep.before(c, "mine iron 3").isEmpty(), "no wooden pickaxe for iron");
		cleanup(m, "GtFrugal");
		ctx.complete();
	}

	/** Sealed in deep under solid rock with the goal above: it digs its way straight up. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "dig_out")
	public void digsOutOfDeepPocket(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtMole");
		for (int x = 0; x <= 8; x++) {
			for (int z = 0; z <= 8; z++) {
				for (int y = 0; y <= 16; y++) {
					ctx.setBlockState(x, y, z, Blocks.STONE);
				}
				for (int y = 17; y <= 19; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		ctx.setBlockState(4, 1, 4, Blocks.AIR);
		ctx.setBlockState(4, 2, 4, Blocks.AIR);
		ctx.assertTrue(m.spawn("GtMole", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtMole");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		c.data.mode = "follow";
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.COBBLESTONE, 4);
		BlockPos top = ctx.getAbsolutePos(new BlockPos(6, 17, 6));
		c.jobs().replace(java.util.List.of("goto " + top.getX() + " " + top.getY() + " " + top.getZ() + " the top"));
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(c.player().getY() >= top.getY() - 0.5, "still down at " + c.player().getBlockPos() + "; " + c.describeActivity());
			cleanup(m, "GtMole");
		});
	}

	/** Companions don't use up player slots: with every slot taken by companions, a real player can still join. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "slots")
	public void companionsDontFillTheServer(TestContext ctx) {
		CompanionManager m = manager(ctx);
		floor(ctx, 8);
		var pm = ctx.getWorld().getServer().getPlayerManager();
		int slots = pm.getMaxPlayerCount();
		java.util.List<String> names = new java.util.ArrayList<>();
		for (int i = 0; pm.getCurrentPlayerCount() < slots + 1 && i < 16; i++) {
			String n = "GtSlot" + i;
			names.add(n);
			m.remove(n);
			ctx.assertTrue(m.spawn(n, "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5 + (i % 6), 1, 1.5 + i / 6)), 0.0F) == null, "spawn " + n);
		}
		ctx.assertTrue(pm.getCurrentPlayerCount() > slots, "companions should be able to go past the slot count");
		var verdict = pm.checkCanJoin(new java.net.InetSocketAddress("127.0.0.1", 25565),
			new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "RealPlayer"));
		ctx.assertTrue(verdict == null, "a real player was turned away: " + (verdict == null ? "" : verdict.getString()) + " (" + slots + " slots)");
		cleanup(m, names.toArray(new String[0]));
		ctx.complete();
	}

	/** Low on air at the bottom of a pool: it drops what it's doing and comes up to breathe. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300, batchId = "breathe")
	public void surfacesForAir(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtGasp");
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
				for (int y = 1; y <= 9; y++) {
					ctx.setBlockState(x, y, z, wall ? (y <= 6 ? Blocks.STONE : Blocks.AIR) : y <= 6 ? Blocks.WATER : Blocks.AIR);
				}
			}
		}
		ctx.assertTrue(m.spawn("GtGasp", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtGasp");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().setAir(60);
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(c.player().isAlive() && !c.player().isSubmergedInWater() && c.player().getAir() > 200,
				"still under: y " + c.player().getY() + ", air " + c.player().getAir() + "; " + c.describeActivity());
			cleanup(m, "GtGasp");
		});
	}

	/**
	 * Standing in a lava pool (fire resistance on, so the test is about getting out, not
	 * surviving): it wades to the edge and climbs out, though path finding sees only lava around.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200, batchId = "lava_escape")
	public void wadesOutOfLava(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtEmber");
		flatArena(ctx, 9);
		for (int x = 0; x < 9; x++) {
			for (int z = 0; z < 9; z++) {
				boolean pool = x >= 1 && x <= 4 && z >= 1 && z <= 4;
				ctx.setBlockState(x, 1, z, pool ? Blocks.LAVA : Blocks.STONE);
			}
		}
		ctx.assertTrue(m.spawn("GtEmber", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtEmber");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE, 400));
		ctx.runAtTick(3, () -> ctx.assertTrue(c.player().isInLava(), "didn't start in the lava"));
		ctx.runAtTick(120, () -> {
			ctx.assertTrue(c.player().isAlive() && !c.player().isInLava(), "still in lava at " + c.player().getBlockPos() + "; " + c.describeActivity());
			cleanup(m, "GtEmber");
			ctx.complete();
		});
	}

	/** Dying in water: it comes back at home, on dry ground, alive (not stuck in a death animation). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "respawn_safe")
	public void respawnsSafelyAtHome(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtPhoenix");
		flatArena(ctx, 12);
		for (int x = 1; x <= 4; x++) {
			for (int z = 1; z <= 4; z++) {
				ctx.setBlockState(x, 1, z, Blocks.WATER);
				ctx.setBlockState(x, 2, z, Blocks.WATER);
			}
		}
		ctx.assertTrue(m.spawn("GtPhoenix", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtPhoenix");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		BlockPos home = ctx.getAbsolutePos(new BlockPos(9, 1, 9));
		c.memory().setPlace("home", home, c.dimension(), ctx.getWorld().getTime());
		var first = c.player();
		first.kill();
		ctx.addInstantFinalTask(() -> {
			var p = c.player();
			ctx.assertTrue(p != null && p != first, "not respawned yet");
			ctx.assertTrue(p.isAlive() && p.getHealth() > 0 && p.deathTime == 0, "came back dead: health " + p.getHealth());
			ctx.assertTrue(!p.isTouchingWater() && p.getBlockPos().getSquaredDistance(home) < 3 * 3, "respawned at " + p.getBlockPos() + " (home " + home + ")");
			cleanup(m, "GtPhoenix");
		});
	}

	/** Asked for two, it still mines the whole vein, including blocks joined only at the corners. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200, batchId = "whole_vein")
	public void minesWholeVein(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtVein");
		solidGround(ctx, Blocks.STONE);
		for (int x = 1; x <= 7; x++) {
			for (int z = 1; z <= 3; z++) {
				for (int y = 5; y <= 6; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR); // a room carved into the top of the stone
				}
			}
		}
		// A diagonal vein in the room's back wall (z = 4), exposed only at its first block.
		BlockPos[] vein = {new BlockPos(3, 5, 4), new BlockPos(4, 6, 5), new BlockPos(5, 5, 6), new BlockPos(6, 6, 5), new BlockPos(4, 4, 5)};
		for (BlockPos b : vein) {
			ctx.setBlockState(b, Blocks.IRON_ORE);
		}
		ctx.assertTrue(m.spawn("GtVein", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 5, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtVein");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		c.data.mode = "follow";
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		c.jobs().replace(java.util.List.of("mine iron 2"));
		ctx.addInstantFinalTask(() -> {
			int left = 0;
			for (BlockPos b : vein) {
				if (ctx.getBlockState(b).isOf(Blocks.IRON_ORE)) {
					left++;
				}
			}
			ctx.assertTrue(left == 0, left + " ore left in the vein; has " + count(c, net.minecraft.item.Items.RAW_IRON) + " raw iron; " + c.describeActivity());
			cleanup(m, "GtVein");
		});
	}

	/** Animals out of close range but in plain view (~40 blocks): it heads straight for them. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "hunt_far")
	public void huntsHerdInTheDistance(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtScout");
		// (The test world only loads a few chunks around each test, so keep it within ~48 blocks.)
		for (int x = -4; x <= 48; x++) {
			for (int z = -4; z <= 8; z++) {
				ctx.setBlockState(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y <= 20; y++) { // tall: other tests' arenas may stand in the way
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		// Walls east and along the sides, so the cows can't wander out of the loaded test area.
		for (int x = 36; x <= 47; x++) {
			for (int y = 1; y <= 2; y++) {
				ctx.setBlockState(x, y, -4, Blocks.STONE_BRICKS);
				ctx.setBlockState(x, y, 8, Blocks.STONE_BRICKS);
			}
		}
		for (int z = -4; z <= 8; z++) {
			for (int y = 1; y <= 2; y++) {
				ctx.setBlockState(47, y, z, Blocks.STONE_BRICKS);
			}
		}
		for (int i = 0; i < 4; i++) {
			ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(41 + i, 1, 2 + (i % 2))).setPersistent();
		}
		ctx.assertTrue(m.spawn("GtScout", "zuri", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtScout");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		// The test world only keeps a few chunks around each test loaded, and the cows' chunk could
		// load its entities well after the test began (depending on which tests sat nearby): told
		// too early, the companion saw no animals and wandered off the loaded area. Keep the strip
		// loaded, and give the order once the cows can be seen.
		BlockPos lo = ctx.getAbsolutePos(new BlockPos(-4, 0, -4));
		BlockPos hi = ctx.getAbsolutePos(new BlockPos(48, 0, 8));
		java.util.List<net.minecraft.util.math.ChunkPos> forced = new java.util.ArrayList<>();
		for (int cx = Math.min(lo.getX(), hi.getX()) >> 4; cx <= Math.max(lo.getX(), hi.getX()) >> 4; cx++) {
			for (int cz = Math.min(lo.getZ(), hi.getZ()) >> 4; cz <= Math.max(lo.getZ(), hi.getZ()) >> 4; cz++) {
				if (ctx.getWorld().setChunkForced(cx, cz, true)) {
					forced.add(new net.minecraft.util.math.ChunkPos(cx, cz));
				}
			}
		}
		boolean[] ordered = {false};
		ctx.addInstantFinalTask(() -> {
			if (!ordered[0] && !ctx.getWorld().getEntitiesByClass(net.minecraft.entity.passive.CowEntity.class,
				c.player().getBoundingBox().expand(64, 16, 64), e -> true).isEmpty()) {
				ordered[0] = true;
				ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "hunt 1 cow", null) == null, "order");
			}
			ctx.assertTrue(count(c, net.minecraft.item.Items.BEEF) >= 1 || count(c, net.minecraft.item.Items.LEATHER) >= 1,
				"no cow; at " + c.player().getBlockPos() + " ahead " + ctx.getWorld().getBlockState(c.player().getBlockPos().east())
					+ "/" + ctx.getWorld().getBlockState(c.player().getBlockPos().east().up()) + " below " + ctx.getWorld().getBlockState(c.player().getBlockPos().down())
					+ " entities " + ctx.getWorld().getOtherEntities(c.player(), c.player().getBoundingBox().expand(1.5)) + "; " + c.describeActivity());
			forced.forEach(cp -> ctx.getWorld().setChunkForced(cp.x, cp.z, false));
			cleanup(m, "GtScout");
		});
	}

	/** Bare-handed with a couple of logs: it makes a wooden sword before going after animals. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200, batchId = "hunt_sword")
	public void craftsSwordBeforeHunting(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtArmed");
		flatArena(ctx, 12);
		for (int i = 0; i < 4; i++) {
			ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(8 + (i % 2), 1, 4 + i * 2)).setPersistent();
		}
		ctx.assertTrue(m.spawn("GtArmed", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 6.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtArmed");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.OAK_LOG, 2);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "hunt 1 cow", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.WOODEN_SWORD) == 1, "no sword; " + c.describeActivity() + "; queued " + c.jobs().pending());
			ctx.assertTrue(count(c, net.minecraft.item.Items.BEEF) >= 1 || count(c, net.minecraft.item.Items.LEATHER) >= 1, "no kill yet; " + c.describeActivity());
			cleanup(m, "GtArmed");
		});
	}

	/** Planning with no table around counts the table's planks too (no "one more log" loop). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "table_budget")
	public void craftPlanCountsTheTable(TestContext ctx) {
		var server = ctx.getWorld().getServer();
		java.util.Map<net.minecraft.item.Item, Integer> inv = new java.util.HashMap<>();
		inv.put(net.minecraft.item.Items.OAK_LOG, 1);
		inv.put(net.minecraft.item.Items.OAK_PLANKS, 2);
		var alone = com.botcraft.knowledge.CraftPlanner.plan(server, inv, net.minecraft.item.Items.WOODEN_PICKAXE, 1);
		var withTable = com.botcraft.knowledge.CraftPlanner.plan(server, inv, net.minecraft.item.Items.WOODEN_PICKAXE, 1, true);
		ctx.assertTrue(alone.ready(), "enough for the pickaxe alone: " + alone.missing());
		ctx.assertTrue(!withTable.ready() && !withTable.missing().isEmpty(), "but not with a table too: " + withTable.missing());
		ctx.complete();
	}

	/** The watchdog flags a job that makes no progress for its whole window, and not one that does. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "watchdog")
	public void watchdogFlagsStalledJob(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtStall");
		floor(ctx, 8);
		ctx.assertTrue(m.spawn("GtStall", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtStall");
		com.botcraft.chat.CommandTasks.stay(c);
		BlockPos far = ctx.getAbsolutePos(new BlockPos(3, 1, 60));
		c.jobs().replace(java.util.List.of("goto " + far.getX() + " " + far.getY() + " " + far.getZ() + " nowhere"));
		c.jobs().tick();
		ctx.assertTrue(c.brain().hasJob(), "job started");
		var dog = new com.botcraft.brain.Watchdog();
		ctx.assertTrue(!dog.check(c, 1000), "first look just records");
		ctx.assertTrue(!dog.check(c, 1000 + com.botcraft.brain.Watchdog.STALL_TICKS / 2), "not yet");
		ctx.assertTrue(dog.check(c, 1000 + com.botcraft.brain.Watchdog.STALL_TICKS + 1), "stalled after the window");
		give(c, net.minecraft.item.Items.COBBLESTONE, 1); // progress: what it carries changed
		ctx.assertTrue(!dog.check(c, 1000 + 2 * com.botcraft.brain.Watchdog.STALL_TICKS + 2), "progress resets it");
		c.jobs().clear();
		ctx.assertTrue(!dog.check(c, 1000 + 4 * com.botcraft.brain.Watchdog.STALL_TICKS), "no job, no stall");
		cleanup(m, "GtStall");
		ctx.complete();
	}

	/** After dying, it goes back for what it dropped. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "recover_gear")
	public void recoversGearAfterDeath(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtLazarus");
		flatArena(ctx, 14);
		ctx.assertTrue(m.spawn("GtLazarus", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtLazarus");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		c.data.mode = "follow";
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.IRON_INGOT, 3);
		give(c, net.minecraft.item.Items.COBBLESTONE, 10);
		BlockPos home = ctx.getAbsolutePos(new BlockPos(11, 1, 11));
		c.memory().setPlace("home", home, c.dimension(), ctx.getWorld().getTime());
		var first = c.player();
		first.kill();
		ctx.addInstantFinalTask(() -> {
			var p = c.player();
			ctx.assertTrue(p != null && p != first, "not respawned yet");
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_INGOT) == 3, "has " + count(c, net.minecraft.item.Items.IRON_INGOT)
				+ " iron back; at " + p.getBlockPos() + "; " + c.describeActivity() + "; queued " + c.jobs().pending()
				+ "; items around " + ctx.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, p.getBoundingBox().expand(16), e -> true)
					.stream().map(e -> e.getStack() + "@" + e.getBlockPos().toShortString() + " owner " + e.getOwner()).toList()
				+ "; ours " + c.leftovers().lying() + " expected " + c.leftovers().anyExpected());
			cleanup(m, "GtLazarus");
		});
	}

	/** Grass field for animal tests: x,z from -2..size, open sky. */
	static void field(TestContext ctx, int size) {
		for (int x = -2; x <= size; x++) {
			for (int z = -2; z <= 8; z++) {
				ctx.setBlockState(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y <= 6; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
	}

	/** Passing by animals is enough to remember where they are. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300, batchId = "herd_watch")
	public void remembersHerdsItSees(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtWatcher");
		field(ctx, 40);
		for (int i = 0; i < 4; i++) {
			ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(32 + (i % 2), 1, 2 + i)).setPersistent();
		}
		ctx.assertTrue(m.spawn("GtWatcher", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtWatcher");
		com.botcraft.chat.CommandTasks.stay(c);
		BlockPos cows = ctx.getAbsolutePos(new BlockPos(32, 1, 3));
		ctx.addInstantFinalTask(() -> {
			var seen = c.memory().sightings("herd", cows, c.dimension(), 12);
			ctx.assertTrue(!seen.isEmpty(), "no herd remembered near " + cows + "; all: " + c.memory().sightings.stream()
				.map(x -> x.kind + "@" + x.pos().toShortString()).toList() + "; cows at " + ctx.getWorld().getEntitiesByClass(
				net.minecraft.entity.passive.CowEntity.class, c.player().getBoundingBox().expand(64), e -> true).stream().map(e -> e.getBlockPos().toShortString()).toList());
			cleanup(m, "GtWatcher");
		});
	}

	/** Starving with nothing to eat, it will take one of a pair (but not when fed). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "hunt_desperate")
	public void starvingTakesOneOfAPair(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtStarving");
		flatArena(ctx, 12);
		var a = (net.minecraft.entity.passive.CowEntity) ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(8, 1, 5));
		var b = (net.minecraft.entity.passive.CowEntity) ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(9, 1, 7));
		a.setPersistent();
		b.setPersistent();
		ctx.assertTrue(!com.botcraft.knowledge.Animals.huntable(a, java.util.List.of(a, b), false), "a fed companion leaves a pair alone");
		ctx.assertTrue(com.botcraft.knowledge.Animals.huntable(a, java.util.List.of(a, b), true), "a starving one may take one");
		ctx.assertTrue(!com.botcraft.knowledge.Animals.huntable(a, java.util.List.of(a), true), "never the very last one");
		ctx.assertTrue(m.spawn("GtStarving", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 6.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtStarving");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		c.player().getHungerManager().setFoodLevel(4);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "hunt 1 cow", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(!a.isAlive() || !b.isAlive(), "neither cow taken; " + c.describeActivity());
			ctx.assertTrue(a.isAlive() || b.isAlive(), "took both");
			cleanup(m, "GtStarving");
		});
	}

	/** Free mode keeps food in reserve: fed but with nothing to eat, it hunts and cooks. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2400, batchId = "stock_food")
	public void freeModeStocksFood(TestContext ctx) {
		clearAnimals(ctx);
		CompanionManager m = manager(ctx);
		cleanup(m, "GtPantry");
		flatArena(ctx, 12);
		for (int i = 0; i < 5; i++) {
			ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(8 + (i % 2), 1, 2 + i * 2)).setPersistent();
		}
		ctx.assertTrue(m.spawn("GtPantry", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 6.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtPantry");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		c.data.mode = "free";
		c.data.initiative = 1.0F;
		c.memory().setPlace("home", ctx.getAbsolutePos(new BlockPos(3, 1, 6)), c.dimension(), ctx.getWorld().getTime());
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.STONE_AXE, 1);
		give(c, net.minecraft.item.Items.OAK_LOG, 6);
		give(c, net.minecraft.item.Items.FURNACE, 1);
		give(c, net.minecraft.item.Items.COAL, 4);
		ctx.getWorld().setTimeOfDay(1000);
		ctx.addInstantFinalTask(() -> {
			int cooked = count(c, net.minecraft.item.Items.COOKED_BEEF);
			ctx.assertTrue(cooked >= 2, "cooked " + cooked + "; raw " + count(c, net.minecraft.item.Items.BEEF) + "; " + c.describeActivity()
				+ "; queued " + c.jobs().pending());
			c.data.initiative = null;
			cleanup(m, "GtPantry");
		});
	}

	static com.botcraft.colony.Colony colony(TestContext ctx, BlockPos base) {
		var colony = com.botcraft.colony.Colony.get();
		ctx.assertTrue(colony != null, "colony loaded");
		colony.clear();
		colony.setBase(ctx.getAbsolutePos(base), ctx.getWorld().getRegistryKey().getValue().toString());
		return colony;
	}

	static int countIn(TestContext ctx, BlockPos chest, net.minecraft.item.Item item) {
		var inv = com.botcraft.colony.Colony.inventory(ctx.getWorld(), ctx.getAbsolutePos(chest));
		int n = 0;
		for (int i = 0; inv != null && i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) {
				n += inv.getStack(i).getCount();
			}
		}
		return n;
	}

	/** "Put your stuff away": the kit stays on it, everything else goes into the base chest. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "deposit")
	public void depositsAllButTheKit(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtStorer");
		flatArena(ctx, 12);
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		ctx.setBlockState(9, 1, 6, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(new BlockPos(9, 1, 6)));
		ctx.assertTrue(m.spawn("GtStorer", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 6.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtStorer");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.WOODEN_PICKAXE, 1);
		give(c, net.minecraft.item.Items.COBBLESTONE, 64);
		give(c, net.minecraft.item.Items.OAK_LOG, 30);
		give(c, net.minecraft.item.Items.IRON_INGOT, 40);
		give(c, net.minecraft.item.Items.DIRT, 20);
		give(c, net.minecraft.item.Items.GRAVEL, 7);
		give(c, net.minecraft.item.Items.COOKED_BEEF, 20);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "put your stuff away", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			BlockPos chest = new BlockPos(9, 1, 6);
			ctx.assertTrue(countIn(ctx, chest, net.minecraft.item.Items.GRAVEL) == 7, "junk not stored; " + c.describeActivity());
			// Working stock stays on hand (16 logs, 32 iron); only the extra goes in.
			ctx.assertTrue(count(c, net.minecraft.item.Items.OAK_LOG) == 16 && countIn(ctx, chest, net.minecraft.item.Items.OAK_LOG) == 14,
				"logs: kept " + count(c, net.minecraft.item.Items.OAK_LOG));
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_INGOT) == 32 && countIn(ctx, chest, net.minecraft.item.Items.IRON_INGOT) == 8,
				"iron: kept " + count(c, net.minecraft.item.Items.IRON_INGOT));
			ctx.assertTrue(countIn(ctx, chest, net.minecraft.item.Items.WOODEN_PICKAXE) == 1, "spare pickaxe not stored");
			ctx.assertTrue(count(c, net.minecraft.item.Items.STONE_PICKAXE) == 1, "kept its best pickaxe");
			int blocks = count(c, net.minecraft.item.Items.COBBLESTONE) + count(c, net.minecraft.item.Items.DIRT);
			ctx.assertTrue(blocks == com.botcraft.colony.Kit.BLOCKS, "kept " + blocks + " building blocks");
			ctx.assertTrue(count(c, net.minecraft.item.Items.COOKED_BEEF) == com.botcraft.colony.Kit.FOOD, "kept 16 food");
			colony.clear();
			cleanup(m, "GtStorer");
		});
	}

	/** No chest at the base yet: it puts one down (from the one it carries) and stores into it. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "deposit_new_chest")
	public void placesAChestWhenNeeded(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtCarpenter");
		flatArena(ctx, 12);
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		ctx.assertTrue(m.spawn("GtCarpenter", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 6.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtCarpenter");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.CHEST, 1);
		give(c, net.minecraft.item.Items.CLAY_BALL, 12);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "put your stuff away", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(colony.chests().size() == 1, "chests " + colony.chests() + "; " + c.describeActivity());
			ctx.assertTrue(colony.count(st -> st.isOf(net.minecraft.item.Items.CLAY_BALL)) == 12, "stock " + colony.stock());
			colony.clear();
			cleanup(m, "GtCarpenter");
		});
	}

	/** Crafting takes what it needs from the base chests, and never from a chest that isn't ours. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "withdraw")
	public void craftsFromBaseStock(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtBuilder");
		flatArena(ctx, 12);
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		BlockPos ours = new BlockPos(10, 1, 9);
		BlockPos theirs = new BlockPos(3, 1, 3);
		ctx.setBlockState(ours, Blocks.CHEST);
		ctx.setBlockState(theirs, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(ours));
		var oursInv = com.botcraft.colony.Colony.inventory(ctx.getWorld(), ctx.getAbsolutePos(ours));
		var theirsInv = com.botcraft.colony.Colony.inventory(ctx.getWorld(), ctx.getAbsolutePos(theirs));
		oursInv.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_INGOT, 3));
		oursInv.setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STICK, 2));
		theirsInv.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_INGOT, 3));
		theirsInv.setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STICK, 2));
		ctx.assertTrue(colony.count(st -> st.isOf(net.minecraft.item.Items.IRON_INGOT)) == 3, "stock counts only our chest");
		ctx.assertTrue(m.spawn("GtBuilder", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtBuilder");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.CRAFTING_TABLE, 1);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "craft an iron pickaxe", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_PICKAXE) == 1, "no pickaxe; " + c.describeActivity() + "; queued " + c.jobs().pending());
			ctx.assertTrue(countIn(ctx, theirs, net.minecraft.item.Items.IRON_INGOT) == 3, "took from someone else's chest");
			ctx.assertTrue(countIn(ctx, ours, net.minecraft.item.Items.IRON_INGOT) == 0, "didn't use the base iron");
			colony.clear();
			cleanup(m, "GtBuilder");
		});
	}

	/**
	 * "make diamond armor": the four pieces, each got the way free mode would get it. The
	 * chestplate waiting in a base chest is taken, the rest crafted from the diamonds on hand,
	 * and all of it ends up worn.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "gear_order")
	public void makesArmourFromOrder(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtArmourer");
		flatArena(ctx, 12);
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		BlockPos chest = new BlockPos(9, 1, 6);
		ctx.setBlockState(chest, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(chest));
		com.botcraft.colony.Colony.inventory(ctx.getWorld(), ctx.getAbsolutePos(chest))
			.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_CHESTPLATE));
		colony.touched();
		ctx.assertTrue(m.spawn("GtArmourer", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 5.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtArmourer");
		com.botcraft.chat.CommandTasks.stay(c);
		c.memory().setPlace("home", ctx.getAbsolutePos(new BlockPos(6, 1, 6)), c.dimension(), ctx.getWorld().getTime());
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.CRAFTING_TABLE, 1);
		give(c, net.minecraft.item.Items.DIAMOND, 16);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "make diamond armor", null) == null, "order not understood");
		ctx.addInstantFinalTask(() -> {
			var p = c.player();
			for (var slot : new net.minecraft.entity.EquipmentSlot[]{net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
				net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
				ctx.assertTrue(p.getEquippedStack(slot).getItem() instanceof net.minecraft.item.ArmorItem a
					&& a.getMaterial() == net.minecraft.item.ArmorMaterials.DIAMOND, "no diamond " + slot.getName() + "; " + c.describeActivity()
					+ "; queued " + c.jobs().pending());
			}
			ctx.assertTrue(countIn(ctx, chest, net.minecraft.item.Items.DIAMOND_CHESTPLATE) == 0, "made a chestplate instead of taking the stored one");
			colony.clear();
			cleanup(m, "GtArmourer");
		});
	}

	/** Heading out to work from the base: better armour and a better pickaxe in the chests come along. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "gear_from_stock")
	public void takesBetterGearFromChests(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtOutfitter");
		flatArena(ctx, 12);
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		BlockPos chest = new BlockPos(9, 1, 6);
		ctx.setBlockState(chest, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(chest));
		var inv = com.botcraft.colony.Colony.inventory(ctx.getWorld(), ctx.getAbsolutePos(chest));
		inv.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE));
		inv.setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_PICKAXE));
		inv.setStack(2, new net.minecraft.item.ItemStack(net.minecraft.item.Items.LEATHER_HELMET)); // nothing on the head yet: take it too
		colony.touched();
		ctx.assertTrue(m.spawn("GtOutfitter", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 5.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtOutfitter");
		com.botcraft.chat.CommandTasks.stay(c);
		c.memory().setPlace("home", ctx.getAbsolutePos(new BlockPos(6, 1, 6)), c.dimension(), ctx.getWorld().getTime());
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "mine 2 stone", null) == null, "order not understood");
		ctx.addInstantFinalTask(() -> {
			var p = c.player();
			ctx.assertTrue(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isOf(net.minecraft.item.Items.IRON_CHESTPLATE),
				"not wearing the iron chestplate; " + c.describeActivity() + "; queued " + c.jobs().pending());
			ctx.assertTrue(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isOf(net.minecraft.item.Items.LEATHER_HELMET), "no helmet");
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_PICKAXE) == 1, "didn't take the iron pickaxe");
			colony.clear();
			cleanup(m, "GtOutfitter");
		});
	}

	/**
	 * Heading for the surface from a cave goes out the way that's open (a tunnel off to the side),
	 * not at the rock straight overhead. No pickaxe or blocks, so digging up isn't an option.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "open_sky")
	public void walksOutToOpenSky(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtMole");
		for (int x = 0; x <= 14; x++) {
			for (int z = 0; z <= 8; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 12; y++) {
					boolean rock = x <= 10;
					boolean room = x >= 2 && x <= 4 && z >= 3 && z <= 5 && y <= 2;
					boolean tunnel = x >= 4 && z == 4 && y <= 2;
					ctx.setBlockState(x, y, z, rock && !room && !tunnel ? Blocks.STONE : Blocks.AIR);
				}
			}
		}
		ctx.assertTrue(m.spawn("GtMole", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtMole");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.nav().moveTo(com.botcraft.nav.Goal.openSky(ctx.getWorld()), false);
		ctx.addInstantFinalTask(() -> {
			c.nav().moveTo(com.botcraft.nav.Goal.openSky(ctx.getWorld()), false);
			ctx.assertTrue(ctx.getWorld().isSkyVisible(c.player().getBlockPos().up()), "still under rock at " + c.player().getBlockPos()
				+ "; nav " + c.nav().state() + " " + c.nav().failReason());
			cleanup(m, "GtMole");
		});
	}

	/**
	 * At the base, crafting uses the base's own table (found on the shared base map) even when
	 * it's beyond the companion's own look around, instead of putting down another one.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 700, batchId = "base_map")
	public void usesTheBaseTable(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtTidy");
		flatArena(ctx, 20);
		var colony = colony(ctx, new BlockPos(10, 1, 10));
		BlockPos table = new BlockPos(18, 1, 10);
		ctx.setBlockState(table, Blocks.CRAFTING_TABLE);
		ctx.assertTrue(m.spawn("GtTidy", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 10.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtTidy");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.CRAFTING_TABLE, 1);
		give(c, net.minecraft.item.Items.IRON_INGOT, 3);
		give(c, net.minecraft.item.Items.STICK, 2);
		ctx.runAtTick(160, () -> ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "craft an iron pickaxe", null) == null, "order"));
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_PICKAXE) == 1, "no pickaxe; " + c.describeActivity());
			ctx.assertTrue(count(c, net.minecraft.item.Items.CRAFTING_TABLE) == 1, "put its own table down instead of using the base's");
			colony.clear();
			cleanup(m, "GtTidy");
		});
	}

	/**
	 * Stuck on top of a chest on a tall pillar (base furniture on dug-out ground), nothing to
	 * dig or build with: it jumps down like a player would, taking the fall damage.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "pillar_jump")
	public void jumpsDownFromPillar(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtPerch");
		flatArena(ctx, 10);
		for (int y = 1; y <= 7; y++) {
			ctx.setBlockState(5, y, 5, Blocks.COBBLESTONE);
		}
		ctx.setBlockState(5, 8, 5, Blocks.CHEST);
		ctx.assertTrue(m.spawn("GtPerch", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 9, 5.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtPerch");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		BlockPos ground = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
		c.nav().moveTo(com.botcraft.nav.Goal.near(ground, 1.5), false);
		ctx.addInstantFinalTask(() -> {
			c.nav().moveTo(com.botcraft.nav.Goal.near(ground, 1.5), false);
			ctx.assertTrue(c.player().isAlive() && c.player().getBlockPos().getY() == ground.getY(), "still up at " + c.player().getBlockPos()
				+ "; nav " + c.nav().state() + " " + c.nav().failReason());
			cleanup(m, "GtPerch");
		});
	}

	/** Mining stone at home leaves the ground the base stands on alone and digs further out. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "base_ground")
	public void sparesTheBaseGround(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtQuarry");
		flatArena(ctx, 24);
		var colony = colony(ctx, new BlockPos(5, 1, 5));
		ctx.assertTrue(m.spawn("GtQuarry", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 5.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtQuarry");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "mine 2 stone", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.COBBLESTONE) >= 2, "no cobblestone; " + c.describeActivity());
			for (int x = 0; x <= 14; x++) {
				for (int z = 0; z <= 14; z++) {
					BlockPos floor = new BlockPos(x, 0, z);
					if ((x - 5) * (x - 5) + (z - 5) * (z - 5) <= 11 * 11) {
						ctx.assertTrue(!ctx.getWorld().getBlockState(ctx.getAbsolutePos(floor)).isAir(), "dug the base floor at " + floor);
					}
				}
			}
			colony.clear();
			cleanup(m, "GtQuarry");
		});
	}

	/**
	 * Base zones get planned on open ground (core, bed row, farm, pen, apart from each other), and
	 * a bed put down at home goes into the bed row.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "zones")
	public void plansZonesAndUsesTheBedRow(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtSettler");
		flatArena(ctx, 16);
		var colony = colony(ctx, new BlockPos(8, 1, 8));
		com.botcraft.colony.BaseMap.clear();
		ctx.assertTrue(m.spawn("GtSettler", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(8.5, 1, 11.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtSettler");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.WHITE_BED, 1);
		BlockPos base = ctx.getAbsolutePos(new BlockPos(8, 1, 8));
		boolean[] ordered = {false};
		ctx.addInstantFinalTask(() -> {
			var core = colony.zone(com.botcraft.colony.Zones.CORE);
			var beds = colony.zone(com.botcraft.colony.Zones.BEDS);
			ctx.assertTrue(core != null && beds != null, "zones not planned yet: " + colony.zones());
			if (!ordered[0]) {
				ordered[0] = true;
				c.jobs().replace(java.util.List.of("place white_bed " + base.getX() + " " + base.getY() + " " + base.getZ()));
			}
			var mine = c.memory().place("bed");
			ctx.assertTrue(mine != null, "no bed down yet; " + c.describeActivity());
			ctx.assertTrue(beds.contains(mine.pos()), "bed at " + mine.pos().toShortString() + ", not in the bed row " + beds);
			var farm = colony.zone(com.botcraft.colony.Zones.FARM);
			var pen = colony.zone(com.botcraft.colony.Zones.PEN);
			ctx.assertTrue(farm != null && pen != null, "no farm or pen: " + colony.zones());
			var home = colony.zone(com.botcraft.colony.Zones.HOME);
			ctx.assertTrue(home != null, "no home zone: " + colony.zones());
			for (var z : colony.zones()) {
				if (z == home) {
					continue;
				}
				ctx.assertTrue(home.contains(z.x1, z.z1) && home.contains(z.x2, z.z2), z + " isn't inside home " + home);
				for (var o : colony.zones()) {
					ctx.assertTrue(z == o || o == home || !z.overlaps(o, 0), z + " overlaps " + o);
				}
			}
			ctx.assertTrue(colony.protectsGround(ctx.getWorld(), base.down(2)), "home ground not protected");
			ctx.assertTrue(!colony.protectsGround(ctx.getWorld(), new BlockPos(home.x2 + 5, base.getY() - 1, home.z2 + 5)),
				"ground outside home protected");
			colony.clear();
			cleanup(m, "GtSettler");
		});
	}

	/**
	 * Natural or built: a tree's log is natural, a cabin's logs, planks, stripped logs and
	 * placed leaves are built, and so is dirt a player put down (the ledger). Built blocks are
	 * never broken, not even when digging.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "builds")
	public void tellsBuiltFromNatural(TestContext ctx) {
		flatArena(ctx, 12);
		var w = ctx.getWorld();
		for (int y = 1; y <= 4; y++) {
			ctx.setBlockState(2, y, 2, Blocks.OAK_LOG);
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx != 0 || dz != 0) {
					ctx.setBlockState(2 + dx, 4, 2 + dz, Blocks.OAK_LEAVES);
				}
			}
		}
		for (int y = 1; y <= 3; y++) {
			ctx.setBlockState(8, y, 2, Blocks.OAK_LOG);
			ctx.setBlockState(8, y, 3, Blocks.OAK_LOG);
		}
		ctx.setBlockState(5, 1, 5, Blocks.OAK_PLANKS);
		ctx.setBlockState(5, 1, 7, Blocks.STRIPPED_OAK_LOG);
		ctx.setBlockState(5, 1, 9, Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
		ctx.setBlockState(9, 1, 9, Blocks.DIRT);
		ctx.setBlockState(10, 1, 9, Blocks.DIRT);
		BlockPos placed = ctx.getAbsolutePos(new BlockPos(9, 1, 9));
		BlockPos wild = ctx.getAbsolutePos(new BlockPos(10, 1, 9));
		com.botcraft.colony.Builds.record(w, placed);
		try {
			java.util.function.BiConsumer<BlockPos, com.botcraft.colony.Builds.Origin> expect = (rel, origin) -> {
				BlockPos a = ctx.getAbsolutePos(rel);
				var got = com.botcraft.colony.Builds.origin(w, a, w.getBlockState(a));
				ctx.assertTrue(got == origin, w.getBlockState(a).getBlock().getName().getString() + " at " + rel.toShortString() + " is " + got + ", not " + origin);
			};
			expect.accept(new BlockPos(2, 2, 2), com.botcraft.colony.Builds.Origin.NATURAL);
			expect.accept(new BlockPos(8, 2, 2), com.botcraft.colony.Builds.Origin.BUILT);
			expect.accept(new BlockPos(5, 1, 5), com.botcraft.colony.Builds.Origin.BUILT);
			expect.accept(new BlockPos(5, 1, 7), com.botcraft.colony.Builds.Origin.BUILT);
			expect.accept(new BlockPos(5, 1, 9), com.botcraft.colony.Builds.Origin.BUILT);
			expect.accept(new BlockPos(2, 4, 3), com.botcraft.colony.Builds.Origin.NATURAL);
			expect.accept(new BlockPos(9, 1, 9), com.botcraft.colony.Builds.Origin.BUILT);
			expect.accept(new BlockPos(10, 1, 9), com.botcraft.colony.Builds.Origin.NATURAL);
			ctx.assertTrue(!com.botcraft.nav.BreakPolicy.canBreakForNav(w, placed, w.getBlockState(placed), true), "would dig someone's dirt");
			ctx.assertTrue(com.botcraft.nav.BreakPolicy.canBreakForNav(w, wild, w.getBlockState(wild), true), "won't dig natural dirt");
		} finally {
			com.botcraft.colony.Builds.forget(w, placed);
		}
		ctx.complete();
	}

	/** What a companion puts down is recorded: a furnace as built, a dirt block (a throwaway) as litter. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200, batchId = "builds_placed")
	public void recordsWhatCompanionsPlace(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtPlacer");
		flatArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtPlacer", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtPlacer");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.FURNACE, 1);
		give(c, net.minecraft.item.Items.DIRT, 1);
		BlockPos furnace = ctx.getAbsolutePos(new BlockPos(4, 1, 6));
		BlockPos dirt = ctx.getAbsolutePos(new BlockPos(6, 1, 4));
		String dim = ctx.getWorld().getRegistryKey().getValue().toString();
		ctx.addInstantFinalTask(() -> {
			if (!ctx.getWorld().getBlockState(furnace).isOf(Blocks.FURNACE)) {
				c.actions().placeBlock(furnace, s -> s.isOf(net.minecraft.item.Items.FURNACE));
			}
			if (!ctx.getWorld().getBlockState(dirt).isOf(Blocks.DIRT)) {
				c.actions().placeBlock(dirt, s -> s.isOf(net.minecraft.item.Items.DIRT));
			}
			ctx.assertTrue(ctx.getWorld().getBlockState(furnace).isOf(Blocks.FURNACE) && ctx.getWorld().getBlockState(dirt).isOf(Blocks.DIRT),
				"not placed yet: " + c.actions().lastPlaceProblem());
			ctx.assertTrue(com.botcraft.colony.Builds.recorded(ctx.getWorld(), furnace), "furnace not recorded as built");
			ctx.assertTrue(!com.botcraft.colony.Builds.recorded(ctx.getWorld(), dirt), "dirt recorded as built");
			var colony = com.botcraft.colony.Colony.get();
			ctx.assertTrue(colony != null && colony.placedByCompanion(dim, dirt), "dirt not recorded as litter");
			com.botcraft.colony.Builds.forget(ctx.getWorld(), furnace);
			colony.forgetPlaced(dim, dirt);
			cleanup(m, "GtPlacer");
		});
	}

	/** At home a path walks round a trench rather than bridging it (a bridge left there is litter). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "home_litter")
	public void homePathsDontBridge(TestContext ctx) {
		flatArena(ctx, 12);
		for (int z = 0; z <= 8; z++) {
			for (int y = -2; y <= 0; y++) {
				ctx.setBlockState(6, y, z, Blocks.AIR);
			}
			ctx.setBlockState(6, -3, z, Blocks.STONE);
		}
		BlockPos start = ctx.getAbsolutePos(new BlockPos(3, 1, 2));
		BlockPos end = ctx.getAbsolutePos(new BlockPos(9, 1, 2));
		PathFinder.Options opts = new PathFinder.Options(false, true, false, 16, 20000, 64, 3);
		PathFinder away = new PathFinder(new Terrain(ctx.getWorld()), start, Goal.near(end, 0.5), opts, new LongOpenHashSet());
		TickBudget.begin(1000);
		ctx.assertTrue(away.step(100000) == PathFinder.Status.FOUND, "no path away from home");
		ctx.assertTrue(away.result().stream().anyMatch(st -> st.type() == com.botcraft.nav.MoveType.BRIDGE), "away from home it should just bridge");
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		colony.setZone(new com.botcraft.colony.Colony.Zone(com.botcraft.colony.Zones.HOME, ctx.getAbsolutePos(new BlockPos(-1, 0, -1)).getX(),
			ctx.getAbsolutePos(new BlockPos(-1, 0, -1)).getZ(), ctx.getAbsolutePos(new BlockPos(12, 0, 12)).getX(),
			ctx.getAbsolutePos(new BlockPos(12, 0, 12)).getZ(), start.getY(), true));
		PathFinder home = new PathFinder(new Terrain(ctx.getWorld()).homeRules(), start, Goal.near(end, 0.5), opts, new LongOpenHashSet());
		TickBudget.begin(1000);
		ctx.assertTrue(home.step(100000) == PathFinder.Status.FOUND, "no path at home");
		ctx.assertTrue(home.result().stream().noneMatch(st -> st.type() == com.botcraft.nav.MoveType.BRIDGE), "bridged at home: "
			+ home.result().stream().map(st -> st.type() + " " + st.pos().toShortString()).toList());
		colony.clear();
		ctx.complete();
	}

	/**
	 * Tidying up home: a dirt pillar a companion left is taken down, a hole a companion dug is
	 * filled, a bump in the core is levelled; planks and someone's dirt in the core stay.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1400, batchId = "upkeep")
	public void tidiesUpHome(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtUpkeep");
		flatArena(ctx, 20);
		var w = ctx.getWorld();
		String dim = w.getRegistryKey().getValue().toString();
		var colony = colony(ctx, new BlockPos(10, 1, 10));
		com.botcraft.colony.BaseMap.clear();
		com.botcraft.colony.Upkeep.clear();
		BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0));
		BlockPos b = ctx.getAbsolutePos(new BlockPos(19, 0, 19));
		BlockPos base = ctx.getAbsolutePos(new BlockPos(10, 1, 10));
		colony.setZone(new com.botcraft.colony.Colony.Zone(com.botcraft.colony.Zones.HOME, a.getX(), a.getZ(), b.getX(), b.getZ(), base.getY(), true));
		colony.setZone(new com.botcraft.colony.Colony.Zone(com.botcraft.colony.Zones.CORE, base.getX() - 3, base.getZ() - 3, base.getX() + 3,
			base.getZ() + 3, base.getY(), true));
		// The other zones by hand too, inside the arena, so planning doesn't put them on other tests' ground.
		for (String kind : java.util.List.of(com.botcraft.colony.Zones.BEDS, com.botcraft.colony.Zones.FARM, com.botcraft.colony.Zones.PEN)) {
			colony.setZone(new com.botcraft.colony.Colony.Zone(kind, a.getX() + 1, a.getZ() + 1, a.getX() + 3, a.getZ() + 3, base.getY(), true));
		}
		BlockPos[] pillar = new BlockPos[3];
		for (int i = 0; i < 3; i++) {
			ctx.setBlockState(15, 1 + i, 15, Blocks.DIRT);
			pillar[i] = ctx.getAbsolutePos(new BlockPos(15, 1 + i, 15));
			colony.recordPlaced(dim, pillar[i]);
		}
		BlockPos hole = ctx.getAbsolutePos(new BlockPos(5, 0, 5));
		ctx.setBlockState(5, 0, 5, Blocks.AIR);
		colony.recordDug(dim, hole);
		BlockPos bump = ctx.getAbsolutePos(new BlockPos(11, 1, 9));
		ctx.setBlockState(11, 1, 9, Blocks.DIRT);
		BlockPos planks = ctx.getAbsolutePos(new BlockPos(9, 1, 11));
		ctx.setBlockState(9, 1, 11, Blocks.OAK_PLANKS);
		BlockPos theirs = ctx.getAbsolutePos(new BlockPos(12, 1, 11));
		ctx.setBlockState(12, 1, 11, Blocks.DIRT);
		com.botcraft.colony.Builds.record(w, theirs);
		ctx.assertTrue(m.spawn("GtUpkeep", "efe", null, w, ctx.getAbsolute(new Vec3d(7.5, 1, 7.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtUpkeep");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.DIRT, 8);
		boolean[] ordered = {false};
		ctx.addInstantFinalTask(() -> {
			var map = com.botcraft.colony.BaseMap.get();
			ctx.assertTrue(map != null && map.complete(), "base map not done yet");
			if (!ordered[0]) {
				ordered[0] = true;
				var work = com.botcraft.colony.Upkeep.find(colony);
				ctx.assertTrue(work.stream().noneMatch(k -> k.pos().equals(planks) || k.pos().equals(theirs)), "would touch built blocks: " + work);
				c.jobs().replace(java.util.List.of("tidy"));
			}
			for (BlockPos p : pillar) {
				ctx.assertTrue(w.getBlockState(p).isAir(), "pillar still up at " + p.toShortString() + "; " + c.describeActivity());
			}
			ctx.assertTrue(w.getBlockState(bump).isAir(), "bump still there; " + c.describeActivity());
			ctx.assertTrue(!w.getBlockState(hole).isAir(), "hole not filled; " + c.describeActivity());
			ctx.assertTrue(!colony.placedByCompanion(dim, hole), "the filled hole counts as litter");
			ctx.assertTrue(w.getBlockState(planks).isOf(Blocks.OAK_PLANKS) && w.getBlockState(theirs).isOf(Blocks.DIRT), "took built blocks");
			com.botcraft.colony.Builds.forget(w, theirs);
			colony.clear();
			com.botcraft.colony.Upkeep.clear();
			cleanup(m, "GtUpkeep");
		});
	}

	/** Free mode ignores the owner entirely: far away, it neither follows nor teleports to them. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "free_no_follow")
	public void freeModeIgnoresOwner(TestContext ctx) {
		for (int x = -2; x <= 64; x++) {
			for (int z = -2; z <= 6; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 3; y++) {
					ctx.setBlockState(x, y, z, Blocks.AIR);
				}
			}
		}
		Companion[] c = ownerAndFollower(ctx, "GtFarOwner", "GtLoner", new Vec3d(62.5, 1, 2.5), new Vec3d(1.5, 1, 2.5));
		Companion free = c[1];
		free.data.mode = "free";
		Vec3d start = free.player().getPos();
		ctx.runAtTick(380, () -> {
			double moved = free.player().getPos().distanceTo(start);
			double toOwner = free.player().distanceTo(c[0].player());
			ctx.assertTrue(toOwner > 40, "went to the owner (" + toOwner + " away now, moved " + moved + ")");
			cleanup(CompanionManager.get(), "GtFarOwner", "GtLoner");
			ctx.complete();
		});
	}

	/** Stuck in free mode: the failsafe sends it home (not to the owner); a following companion is left alone. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60, batchId = "failsafe")
	public void failsafeSendsStuckHome(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtStuck");
		flatArena(ctx, 14);
		ctx.assertTrue(m.spawn("GtStuck", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtStuck");
		com.botcraft.chat.CommandTasks.stay(c);
		// Home 40 blocks off (standing still near home is resting, not stuck), on a small pad.
		for (int x = 0; x <= 4; x++) {
			for (int z = 38; z <= 42; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				ctx.setBlockState(x, 1, z, Blocks.AIR);
				ctx.setBlockState(x, 2, z, Blocks.AIR);
			}
		}
		BlockPos home = ctx.getAbsolutePos(new BlockPos(2, 1, 40));
		c.memory().setPlace("home", home, c.dimension(), ctx.getWorld().getTime());
		var safe = new com.botcraft.brain.Failsafe();
		c.data.mode = "follow";
		ctx.assertTrue(safe.check(c, 0, home) == null && safe.check(c, com.botcraft.brain.Failsafe.STUCK_TICKS * 2, home) == null,
			"following: not the failsafe's business");
		c.data.mode = "free";
		long t = com.botcraft.brain.Failsafe.STUCK_TICKS * 3; // after the follow-mode checks above
		ctx.assertTrue(safe.check(c, t, home) == null, "first look just records");
		// Looked at every second, like in a game.
		String why = null;
		for (long dt = 20; dt <= com.botcraft.brain.Failsafe.STUCK_TICKS + 40 && why == null; dt += 20) {
			why = safe.check(c, t + dt, home);
			ctx.assertTrue(why == null || dt > com.botcraft.brain.Failsafe.STUCK_TICKS, "tripped early at " + dt);
		}
		ctx.assertTrue(why != null, "stuck for 5 minutes should trip it");
		c.rescueHome(why);
		ctx.assertTrue(c.player().getBlockPos().getSquaredDistance(home) < 4 * 4, "rescued to " + c.player().getBlockPos() + ", home " + home);
		cleanup(m, "GtStuck");
		ctx.complete();
	}

	/** Chests with a purpose: ores go to the ore chest, everything else to the general chest. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "deposit_purpose")
	public void storesByChestPurpose(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtSorter");
		flatArena(ctx, 12);
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		BlockPos oreChest = new BlockPos(9, 1, 3);
		BlockPos general = new BlockPos(9, 1, 9);
		ctx.setBlockState(oreChest, Blocks.CHEST);
		ctx.setBlockState(general, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(oreChest));
		colony.addChest(ctx.getAbsolutePos(general));
		colony.setPurpose(ctx.getAbsolutePos(oreChest), com.botcraft.colony.Purpose.ORES);
		ctx.assertTrue(m.spawn("GtSorter", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 9.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtSorter");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.RAW_GOLD, 9);
		give(c, net.minecraft.item.Items.GRAVEL, 12);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "put your stuff away", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(countIn(ctx, oreChest, net.minecraft.item.Items.RAW_GOLD) == 9, "gold not in the ore chest; " + c.describeActivity());
			ctx.assertTrue(countIn(ctx, general, net.minecraft.item.Items.GRAVEL) == 12, "gravel not in the general chest");
			ctx.assertTrue(countIn(ctx, oreChest, net.minecraft.item.Items.GRAVEL) == 0, "gravel went in the ore chest");
			colony.clear();
			cleanup(m, "GtSorter");
		});
	}

	static Companion plannerSubject(TestContext ctx, String name) {
		CompanionManager m = manager(ctx);
		cleanup(m, name);
		flatArena(ctx, 12);
		ctx.assertTrue(m.spawn(name, "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName(name);
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		return c;
	}

	/** Planner: iron and sticks in the base chest beat mining for an iron pickaxe. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "planner_chests")
	public void plannerUsesStock(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtPlanA");
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		ctx.setBlockState(9, 1, 6, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(new BlockPos(9, 1, 6)));
		var inv = com.botcraft.colony.Colony.inventory(ctx.getWorld(), ctx.getAbsolutePos(new BlockPos(9, 1, 6)));
		inv.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_INGOT, 3));
		inv.setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STICK, 2));
		give(c, net.minecraft.item.Items.CRAFTING_TABLE, 1);
		var d = com.botcraft.brain.plan.Planner.acquire(c, net.minecraft.item.Items.IRON_PICKAXE, 1);
		ctx.assertTrue(d.chosen().steps().contains("withdraw iron_ingot 3") && d.chosen().steps().stream().noneMatch(st -> st.startsWith("mine")),
			"plan " + d.chosen() + " | " + d.considered());
		colony.clear();
		cleanup(CompanionManager.get(), "GtPlanA");
		ctx.complete();
	}

	/** Planner: raw iron already carried (with fuel) gets smelted, not mined again. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "planner_smelt")
	public void plannerSmeltsWhatItHas(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtPlanB");
		give(c, net.minecraft.item.Items.RAW_IRON, 3);
		give(c, net.minecraft.item.Items.COAL, 2);
		give(c, net.minecraft.item.Items.FURNACE, 1);
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		var d = com.botcraft.brain.plan.Planner.acquire(c, net.minecraft.item.Items.IRON_INGOT, 3);
		ctx.assertTrue(d.chosen().method().equals("smelt") && d.chosen().steps().equals(java.util.List.of("smelt iron_ingot 3")),
			"plan " + d.chosen() + " | " + d.considered());
		cleanup(CompanionManager.get(), "GtPlanB");
		ctx.complete();
	}

	/** Planner: from nothing but a stone pickaxe, an iron pickaxe means mine, smelt, craft (with sticks from wood). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "planner_chain")
	public void plannerChainsFromScratch(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtPlanC");
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		var d = com.botcraft.brain.plan.Planner.acquire(c, net.minecraft.item.Items.IRON_PICKAXE, 1);
		var steps = d.chosen().steps();
		int mine = steps.indexOf("mine iron 3");
		int smelt = steps.indexOf("smelt iron_ingot 3");
		int craft = steps.indexOf("craft iron_pickaxe 1");
		ctx.assertTrue(mine >= 0 && smelt > mine && craft > smelt, "steps " + steps);
		ctx.assertTrue(steps.stream().anyMatch(st -> st.startsWith("gather logs")), "needs wood for sticks: " + steps);
		cleanup(CompanionManager.get(), "GtPlanC");
		ctx.complete();
	}

	/** Planner: a diamond pickaxe from an empty inventory (about a dozen steps deep), planned quickly. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "planner_deep")
	public void plannerPlansDiamondsFromNothing(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtPlanDeep");
		long t0 = System.nanoTime();
		var d = com.botcraft.brain.plan.Planner.acquire(c, net.minecraft.item.Items.DIAMOND_PICKAXE, 1);
		double ms = (System.nanoTime() - t0) / 1e6;
		var steps = d.chosen().steps();
		ctx.assertTrue(d.chosen().possible(), "no plan: " + d.chosen().note());
		int iron = steps.indexOf("craft iron_pickaxe 1");
		int diamonds = steps.indexOf("mine diamond 3");
		ctx.assertTrue(steps.stream().anyMatch(st -> st.startsWith("gather logs")) && iron >= 0 && diamonds > iron, "steps " + steps);
		ctx.assertTrue(ms < 50, "planning took " + ms + " ms");
		cleanup(CompanionManager.get(), "GtPlanDeep");
		ctx.complete();
	}

	/** Learned costs: a method that keeps failing and killing us gets more expensive. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "planner_learning")
	public void plannerLearnsFromFailure(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtPlanD");
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		double before = com.botcraft.brain.plan.Planner.acquire(c, net.minecraft.item.Items.RAW_IRON, 3).chosen().cost();
		for (int i = 0; i < 6; i++) {
			com.botcraft.brain.plan.MethodStats.record(c, "mine iron", false, 0, 20 * 120);
		}
		com.botcraft.brain.plan.MethodStats.death(c, "mine iron");
		double after = com.botcraft.brain.plan.Planner.acquire(c, net.minecraft.item.Items.RAW_IRON, 3).chosen().cost();
		ctx.assertTrue(after > before * 2, "cost " + before + " -> " + after);
		cleanup(CompanionManager.get(), "GtPlanD");
		ctx.complete();
	}

	/** Targets: the ladder is followed in order (iron pickaxe, then sword, then armour...). */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "targets")
	public void targetsInOrder(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtLadder");
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		var t = com.botcraft.brain.plan.Targets.next(c, x -> true);
		ctx.assertTrue(t != null && t.item() == net.minecraft.item.Items.IRON_PICKAXE, "first " + t);
		give(c, net.minecraft.item.Items.IRON_PICKAXE, 1);
		t = com.botcraft.brain.plan.Targets.next(c, x -> true);
		ctx.assertTrue(t != null && t.item() == net.minecraft.item.Items.IRON_SWORD, "then " + t);
		give(c, net.minecraft.item.Items.IRON_SWORD, 1);
		give(c, net.minecraft.item.Items.IRON_CHESTPLATE, 1);
		t = com.botcraft.brain.plan.Targets.next(c, x -> true);
		ctx.assertTrue(t != null && t.item() == net.minecraft.item.Items.IRON_LEGGINGS, "then " + t);
		cleanup(CompanionManager.get(), "GtLadder");
		ctx.complete();
	}

	/** "Dance!": it dances, then goes back to what it was doing. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100, batchId = "dance")
	public void dancesOnRequest(TestContext ctx) {
		Companion c = plannerSubject(ctx, "GtDancer");
		var parsed = com.botcraft.chat.IntentParser.parse("GtDancer dance", java.util.List.of("GtDancer"));
		ctx.assertTrue(com.botcraft.chat.ChatRouter.respond(c, null, parsed.intents(), false) == null, "respond");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue("dance".equals(c.brain().jobName()), "not dancing: " + c.describeActivity());
			cleanup(CompanionManager.get(), "GtDancer");
		});
	}

	/** A sealed, unlit stone room (x,z 0..size, floor y0, air y1..3, roof y4). */
	static void darkRoom(TestContext ctx, int size) {
		for (int x = -1; x <= size + 1; x++) {
			for (int z = -1; z <= size + 1; z++) {
				boolean wall = x < 0 || z < 0 || x > size || z > size;
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 3; y++) {
					ctx.setBlockState(x, y, z, wall ? Blocks.STONE : Blocks.AIR);
				}
				ctx.setBlockState(x, 4, z, Blocks.STONE);
			}
		}
	}

	/** Underground in the dark with torches: it puts one down beside it. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200, batchId = "cave_torch")
	public void lightsDarkCaves(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtCaver");
		darkRoom(ctx, 6);
		ctx.assertTrue(m.spawn("GtCaver", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtCaver");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.TORCH, 8);
		ctx.addInstantFinalTask(() -> {
			int torches = 0;
			for (BlockPos b : BlockPos.iterate(ctx.getAbsolutePos(new BlockPos(0, 1, 0)), ctx.getAbsolutePos(new BlockPos(6, 1, 6)))) {
				if (ctx.getWorld().getBlockState(b).isOf(Blocks.TORCH)) {
					torches++;
				}
			}
			ctx.assertTrue(torches >= 1, "no torch placed; carrying " + count(c, net.minecraft.item.Items.TORCH));
			cleanup(m, "GtCaver");
		});
	}

	/** "Light up the base": no pitch-dark floor left inside a dark base room. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900, batchId = "light_home")
	public void lightsUpTheBase(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtLamplighter");
		darkRoom(ctx, 10);
		var colony = colony(ctx, new BlockPos(5, 1, 5));
		ctx.assertTrue(m.spawn("GtLamplighter", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(5.5, 1, 5.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtLamplighter");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.TORCH, 16);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "light up the base", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			int dark = 0;
			int torches = 0;
			for (BlockPos b : BlockPos.iterate(ctx.getAbsolutePos(new BlockPos(0, 1, 0)), ctx.getAbsolutePos(new BlockPos(10, 1, 10)))) {
				if (ctx.getWorld().getBlockState(b).isOf(Blocks.TORCH)) {
					torches++;
				} else if (ctx.getWorld().getBlockState(b).isAir() && ctx.getWorld().getLightLevel(net.minecraft.world.LightType.BLOCK, b) < 1) {
					dark++;
				}
			}
			ctx.assertTrue(torches >= 1 && !"light_home".equals(c.brain().jobName()), "job still going or no torch: " + torches + "; " + c.describeActivity());
			ctx.assertTrue(dark == 0, dark + " dark spots left; " + c.describeActivity() + "; torches left " + count(c, net.minecraft.item.Items.TORCH));
			colony.clear();
			cleanup(m, "GtLamplighter");
		});
	}

	/** An open arena walled with plain stone (which popbob doesn't grief). */
	static void stoneArena(TestContext ctx, int size) {
		for (int x = -1; x <= size; x++) {
			for (int z = -1; z <= size; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 4; y++) {
					boolean wall = x == -1 || z == -1 || x == size || z == size;
					ctx.setBlockState(x, y, z, wall && y <= 2 ? Blocks.STONE : Blocks.AIR);
				}
			}
		}
	}

	/** Easter egg: popbob goes for the nearest companion, and that companion fights back. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "popbob_fight")
	public void popbobAttacksAndGetsHitBack(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "popbob", "GtVictim");
		stoneArena(ctx, 10);
		var server = ctx.getWorld().getServer();
		boolean pvp = server.isPvpEnabled();
		server.setPvpEnabled(true); // off on the test server; players can't hurt each other without it
		ctx.assertTrue(m.spawn("popbob", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 5.5)), 0.0F) == null, "spawn popbob");
		ctx.assertTrue(m.spawn("GtVictim", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(8.5, 1, 5.5)), 0.0F) == null, "spawn victim");
		Companion bob = m.byName("popbob");
		Companion victim = m.byName("GtVictim");
		bob.player().getInventory().clear();
		victim.player().getInventory().clear();
		com.botcraft.chat.CommandTasks.stay(victim);
		boolean[] seen = new boolean[3]; // victim hurt, victim fought, popbob hurt
		ctx.addInstantFinalTask(() -> {
			seen[0] |= victim.player().getHealth() < victim.player().getMaxHealth();
			seen[1] |= victim.brain().currentObjective() == com.botcraft.brain.Objective.FIGHT;
			seen[2] |= bob.player().getHealth() < bob.player().getMaxHealth();
			ctx.assertTrue(seen[0], "popbob never hit the victim; popbob at " + bob.player().getPos() + ", victim " + victim.player().getPos());
			ctx.assertTrue(seen[1], "victim never fought back: " + victim.describeActivity());
			ctx.assertTrue(seen[2], "popbob never got hit");
			server.setPvpEnabled(pvp);
			cleanup(m, "popbob", "GtVictim");
		});
	}

	/** Easter egg: with nobody around, popbob griefs a build (planks) but leaves containers alone. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "popbob_grief")
	public void popbobGriefsButSparesChests(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "popbob");
		stoneArena(ctx, 8);
		ctx.setBlockState(5, 1, 3, Blocks.OAK_PLANKS);
		ctx.setBlockState(5, 1, 5, Blocks.CHEST);
		ctx.assertTrue(m.spawn("popbob", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion bob = m.byName("popbob");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(ctx.getBlockState(new BlockPos(5, 1, 3)).isAir(), "planks still there; at " + bob.player().getPos());
			ctx.assertTrue(ctx.getBlockState(new BlockPos(5, 1, 5)).isOf(Blocks.CHEST), "chest was broken");
			cleanup(m, "popbob");
		});
	}

	/** Easter egg: popbob doesn't take orders. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "popbob_orders")
	public void popbobIgnoresOrders(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "popbob");
		stoneArena(ctx, 6);
		ctx.assertTrue(m.spawn("popbob", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion bob = m.byName("popbob");
		var parsed = com.botcraft.chat.IntentParser.parse("popbob get 10 logs", java.util.List.of("popbob"));
		ctx.assertTrue(com.botcraft.chat.ChatRouter.respond(bob, null, parsed.intents(), false) == null, "respond");
		ctx.assertTrue(bob.jobs().isIdle(), "popbob took the order");
		cleanup(m, "popbob");
		ctx.complete();
	}

	/** "Everyone run away": free mode, and each heads off 40-64 blocks, in different directions. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "scatter")
	public void scattersOnRunAway(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtScatterA", "GtScatterB");
		stoneArena(ctx, 6);
		ctx.assertTrue(m.spawn("GtScatterA", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 3.5)), 0.0F) == null, "spawn a");
		ctx.assertTrue(m.spawn("GtScatterB", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 3.5)), 0.0F) == null, "spawn b");
		var names = java.util.List.of("GtScatterA", "GtScatterB");
		java.util.List<BlockPos> targets = new java.util.ArrayList<>();
		for (String n : names) {
			Companion c = m.byName(n);
			var parsed = com.botcraft.chat.IntentParser.parse("everyone run away", names);
			ctx.assertTrue(com.botcraft.chat.ChatRouter.respond(c, null, parsed.intents(), false) == null, "respond");
			ctx.assertTrue("free".equals(c.data.mode), "not free: " + c.data.mode);
			String job = c.jobs().pending().isEmpty() ? "" : c.jobs().pending().get(0);
			ctx.assertTrue(job.startsWith("goto "), "no trip queued: " + c.jobs().pending());
			String[] w = job.split(" ");
			BlockPos to = new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
			double d = Math.sqrt(to.getSquaredDistance(c.player().getBlockPos()));
			ctx.assertTrue(d > 35 && d < 70, n + " heading " + (int) d + " blocks off");
			targets.add(to);
		}
		double apart = Math.sqrt(targets.get(0).getSquaredDistance(targets.get(1)));
		ctx.assertTrue(apart > 25, "headed only " + (int) apart + " apart");
		cleanup(m, "GtScatterA", "GtScatterB");
		ctx.complete();
	}

	/**
	 * "Sleep" with a bed in its pockets and none around: it puts the bed down and sleeps, and the
	 * night is skipped even though another companion is still up.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "sleep_own_bed")
	public void placesItsBedAndSkipsTheNight(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtSleeper", "GtNightOwl");
		stoneArena(ctx, 8);
		ctx.assertTrue(m.spawn("GtSleeper", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 4.5)), 0.0F) == null, "spawn");
		ctx.assertTrue(m.spawn("GtNightOwl", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(7.5, 1, 7.5)), 0.0F) == null, "spawn owl");
		Companion c = m.byName("GtSleeper");
		Companion owl = m.byName("GtNightOwl");
		com.botcraft.chat.CommandTasks.stay(c);
		com.botcraft.chat.CommandTasks.stay(owl);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.RED_BED, 1);
		var rules = ctx.getWorld().getGameRules();
		boolean cycle = rules.getBoolean(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE);
		rules.get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(true, ctx.getWorld().getServer());
		ctx.getWorld().setTimeOfDay(18000);
		ctx.assertTrue(com.botcraft.chat.CommandTasks.apply(c, "sleep", null) == null, "order");
		boolean[] slept = new boolean[1];
		ctx.addInstantFinalTask(() -> {
			slept[0] |= c.player().isSleeping();
			ctx.assertTrue(slept[0], "never slept: " + c.describeActivity() + "; bed carried " + count(c, net.minecraft.item.Items.RED_BED));
			ctx.assertTrue(ctx.getWorld().isDay() && !owl.player().isSleeping(), "night not skipped (time " + ctx.getWorld().getTimeOfDay() % 24000 + ")");
			rules.get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(cycle, ctx.getWorld().getServer());
			ctx.getWorld().setTimeOfDay(1000);
			cleanup(m, "GtSleeper", "GtNightOwl");
		});
	}

	/** Free mode at night with wool and planks but no bed: it makes one and goes to sleep. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200, batchId = "free_makes_bed")
	public void freeModeMakesABedAtNight(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtBedMaker");
		stoneArena(ctx, 10);
		ctx.assertTrue(m.spawn("GtBedMaker", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtBedMaker");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.WHITE_WOOL, 3);
		give(c, net.minecraft.item.Items.OAK_PLANKS, 8);
		c.data.owner = null;
		c.data.mode = "free";
		c.data.initiative = 1.0F;
		c.memory().forgetPlace("bed");
		c.memory().setPlace("home", ctx.getAbsolutePos(new BlockPos(5, 1, 5)), c.dimension(), ctx.getWorld().getTime());
		ctx.getWorld().setTimeOfDay(18000);
		boolean[] slept = new boolean[1];
		ctx.addInstantFinalTask(() -> {
			slept[0] |= c.player().isSleeping();
			ctx.assertTrue(slept[0], "never slept: " + c.describeActivity() + "; queued " + c.jobs().pending()
				+ "; beds " + count(c, net.minecraft.item.Items.WHITE_BED) + ", wool " + count(c, net.minecraft.item.Items.WHITE_WOOL));
			c.data.initiative = null;
			ctx.getWorld().setTimeOfDay(1000);
			cleanup(m, "GtBedMaker");
		});
	}

	/** A base with one chest, collecting only {@code key} (up to {@code want}). */
	static com.botcraft.colony.Colony boardBase(TestContext ctx, String key, int want) {
		var colony = colony(ctx, new BlockPos(6, 1, 6));
		ctx.setBlockState(9, 1, 6, Blocks.CHEST);
		colony.addChest(ctx.getAbsolutePos(new BlockPos(9, 1, 6)));
		for (var t : com.botcraft.colony.StockTargets.DEFAULTS) {
			colony.setWant(t.key(), t.key().equals(key) ? want : 0);
		}
		return colony;
	}

	static Companion freeCompanion(TestContext ctx, CompanionManager m, String name, Vec3d at) {
		ctx.assertTrue(m.spawn(name, "kai", null, ctx.getWorld(), ctx.getAbsolute(at), 0.0F) == null, "spawn " + name);
		Companion c = m.byName(name);
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.data.owner = null;
		c.data.mode = "free";
		c.memory().setPlace("home", ctx.getAbsolutePos(new BlockPos(6, 1, 6)), c.dimension(), ctx.getWorld().getTime());
		return c;
	}

	/**
	 * The base is short of coal: the board posts a request and it goes to the companion with spare
	 * coal by the base, not to the one who'd have to go and mine it. Failing it counts against the
	 * next bid.
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "board_bids")
	public void boardGivesRequestToBestBidder(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtRich", "GtPoor");
		flatArena(ctx, 12);
		boardBase(ctx, "coal", 8);
		Companion rich = freeCompanion(ctx, m, "GtRich", new Vec3d(7.5, 1, 7.5));
		Companion poor = freeCompanion(ctx, m, "GtPoor", new Vec3d(2.5, 1, 2.5));
		give(rich, net.minecraft.item.Items.COAL, 30);
		give(poor, net.minecraft.item.Items.STONE_PICKAXE, 1);
		long now = m.tickCount();
		com.botcraft.brain.Board.refresh(m, now);
		var open = com.botcraft.brain.Board.requests();
		ctx.assertTrue(open.size() == 1 && open.get(0).key.equals("coal") && open.get(0).count == 8, "requests: " + open.size());
		ctx.assertTrue(com.botcraft.brain.Board.take(poor, now) == null, "the miner took it");
		var a = com.botcraft.brain.Board.take(rich, now);
		ctx.assertTrue(a != null && a.steps().size() == 2 && a.steps().get(0).startsWith("goto ") && a.steps().get(1).equals("deposit coal 8"),
			"rich didn't get it: " + (a == null ? null : a.steps()));
		var r = a.request();
		ctx.assertTrue(r.bids.get(0).who().equals("GtRich"), "best bid " + r.bids.get(0).who());
		var poorBid = r.bids.stream().filter(b -> b.who().equals("GtPoor")).findFirst().orElse(null);
		ctx.assertTrue(poorBid != null && poorBid.terms().get("time") < -0.5, "the miner's bid should cost time: " + (poorBid == null ? null : poorBid.explain()));
		com.botcraft.brain.Board.finished(r.id, "GtRich", false, now);
		ctx.assertTrue(r.state == com.botcraft.brain.Board.State.OPEN, "not reopened");
		var again = com.botcraft.brain.Board.take(rich, now + 1);
		ctx.assertTrue(again != null && again.request().bids.get(0).terms().containsKey("failed"), "failure not remembered");
		cleanup(m, "GtRich", "GtPoor");
		ctx.complete();
	}

	/** End to end: a free companion with spare coal takes the request and stores a batch in the base chest. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "board_stock")
	public void freeCompanionFillsBaseStock(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtSupplier");
		flatArena(ctx, 12);
		var colony = boardBase(ctx, "coal", 8);
		boolean torches = com.botcraft.config.CompanionConfig.get().placeTorches;
		com.botcraft.config.CompanionConfig.get().placeTorches = false; // (the open-air test base counts as dark)
		Companion c = freeCompanion(ctx, m, "GtSupplier", new Vec3d(4.5, 1, 6.5));
		c.data.initiative = 1.0F;
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		give(c, net.minecraft.item.Items.STONE_PICKAXE, 1);
		give(c, net.minecraft.item.Items.STONE_AXE, 1);
		give(c, net.minecraft.item.Items.BREAD, 10);
		give(c, net.minecraft.item.Items.OAK_LOG, 16);
		give(c, net.minecraft.item.Items.COAL, 30);
		com.botcraft.brain.Board.refresh(m, m.tickCount());
		ctx.setBlockState(2, 1, 10, Blocks.RED_BED.getDefaultState().with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.SOUTH)
			.with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD));
		ctx.setBlockState(2, 1, 9, Blocks.RED_BED.getDefaultState().with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.SOUTH));
		c.memory().setPlace("bed", ctx.getAbsolutePos(new BlockPos(2, 1, 10)), c.dimension(), ctx.getWorld().getTime());
		ctx.getWorld().setTimeOfDay(1000);
		ctx.addInstantFinalTask(() -> {
			int stored = countIn(ctx, new BlockPos(9, 1, 6), net.minecraft.item.Items.COAL);
			ctx.assertTrue(stored >= 8, "stored " + stored + " coal; " + c.describeActivity() + "; queued " + c.jobs().pending()
				+ "; board " + com.botcraft.brain.Board.requests().stream().map(r -> r.describe() + " " + r.state + " " + r.note).toList());
			ctx.assertTrue(com.botcraft.brain.Board.holding("GtSupplier") == null, "still holding the request");
			com.botcraft.config.CompanionConfig.get().placeTorches = torches;
			c.data.initiative = null;
			colony.clear();
			cleanup(m, "GtSupplier");
		});
	}

	/**
	 * Hits land charged: an iron sword (6) kills a cow (10 health) in two hits. (Swinging before
	 * hitting used to reset the attack charge, so every blow did 20% damage.)
	 */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200, batchId = "melee_damage")
	public void hitsLandAtFullDamage(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtSwinger");
		clearAnimals(ctx);
		stoneArena(ctx, 6);
		ctx.assertTrue(m.spawn("GtSwinger", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtSwinger");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.IRON_SWORD, 1);
		var cow = ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(3, 1, 3));
		cow.setAiDisabled(true);
		int[] hits = {0};
		ctx.addInstantFinalTask(() -> {
			if (cow.isAlive()) {
				com.botcraft.action.Inventories.equipBestWeapon(c.player());
				// Face it: this is about the damage, not the aim (a companion from a neighbouring test
				// walking by drew glances that kept the aim off).
				c.player().lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, cow.getBoundingBox().getCenter());
				if (c.actions().attack(cow)) {
					hits[0]++;
				}
			}
			ctx.assertTrue(!cow.isAlive(), "cow alive after " + hits[0] + " hits, health " + cow.getHealth());
			ctx.assertTrue(hits[0] <= 3, "took " + hits[0] + " hits (an iron sword needs 2, 3 with a whiff)");
			cleanup(m, "GtSwinger");
		});
	}

	/**
	 * Two dry rooms joined only by a flooded tunnel (1 wide, 2 high, roofed): A* goes through a
	 * short one, but never through one longer than a breath lasts.
	 */
	static void floodedTunnel(TestContext ctx, int length) {
		int end = 3 + length;
		for (int x = -1; x <= end + 4; x++) {
			for (int z = -1; z <= 1; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				boolean room = x <= 2 || x > end;
				boolean endWall = x == -1 || x == end + 4;
				for (int y = 1; y <= 3; y++) {
					boolean inside = z == 0 && !endWall && (room || y <= 2);
					ctx.setBlockState(x, y, z, !inside ? Blocks.STONE : room ? Blocks.AIR : Blocks.WATER);
				}
			}
		}
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "nav_breath")
	public void pathsNeverOutlastABreath(TestContext ctx) {
		Terrain terrain = new Terrain(ctx.getWorld());
		floodedTunnel(ctx, 6);
		PathFinder shortOne = new PathFinder(terrain, ctx.getAbsolutePos(new BlockPos(1, 1, 0)), Goal.near(ctx.getAbsolutePos(new BlockPos(11, 1, 0)), 0.5),
			PathFinder.Options.basic(20000, 64), new LongOpenHashSet());
		TickBudget.begin(1000);
		ctx.assertTrue(shortOne.step(100000) == PathFinder.Status.FOUND, "no way through a 6-block flooded tunnel");
		floodedTunnel(ctx, 24);
		terrain = new Terrain(ctx.getWorld());
		PathFinder longOne = new PathFinder(terrain, ctx.getAbsolutePos(new BlockPos(1, 1, 0)), Goal.near(ctx.getAbsolutePos(new BlockPos(29, 1, 0)), 0.5),
			PathFinder.Options.basic(20000, 64), new LongOpenHashSet());
		TickBudget.begin(1000);
		ctx.assertTrue(longOne.step(100000) != PathFinder.Status.FOUND, "pathed through a 24-block flooded tunnel");
		ctx.complete();
	}

	/** Out of air in a flooded chamber whose only way out is sideways, then up: it swims that way out. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "breathe_route")
	public void swimsTheWayOutToAir(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtDiver");
		for (int x = -1; x <= 8; x++) {
			for (int z = -1; z <= 5; z++) {
				for (int y = 0; y <= 9; y++) {
					ctx.setBlockState(x, y, z, Blocks.STONE);
				}
			}
		}
		for (int x = 1; x <= 3; x++) {
			for (int z = 1; z <= 3; z++) {
				for (int y = 1; y <= 4; y++) {
					ctx.setBlockState(x, y, z, Blocks.WATER); // the chamber, roofed over
				}
			}
		}
		for (int x = 4; x <= 6; x++) {
			ctx.setBlockState(x, 1, 2, Blocks.WATER); // the tunnel east
			ctx.setBlockState(x, 2, 2, Blocks.WATER);
		}
		for (int y = 3; y <= 6; y++) {
			ctx.setBlockState(6, y, 2, Blocks.WATER); // the shaft up
		}
		ctx.setBlockState(6, 7, 2, Blocks.AIR);
		ctx.setBlockState(6, 8, 2, Blocks.AIR);
		ctx.setBlockState(6, 9, 2, Blocks.AIR);
		ctx.assertTrue(m.spawn("GtDiver", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtDiver");
		com.botcraft.chat.CommandTasks.stay(c);
		c.player().setAir(120);
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(c.player().isAlive() && !c.player().isSubmergedInWater() && c.player().getAir() > 200,
				"still under at " + c.player().getBlockPos() + ", air " + c.player().getAir() + "; " + c.describeActivity());
			cleanup(m, "GtDiver");
		});
	}

	/** A ladder shaft out of a pit, optionally with a waterfall pouring down right beside the ladder. */
	static void ladderPit(TestContext ctx, boolean waterfall) {
		for (int x = 0; x <= 3; x++) {
			for (int z = 0; z <= 2; z++) {
				for (int y = 0; y <= 13; y++) {
					ctx.setBlockState(x, y, z, Blocks.STONE);
				}
			}
		}
		for (int y = 1; y <= 12; y++) {
			ctx.setBlockState(1, y, 1, y <= 10 ? Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, net.minecraft.util.math.Direction.SOUTH)
				: Blocks.AIR.getDefaultState());
			if (waterfall) {
				ctx.setBlockState(2, y, 1, Blocks.WATER.getDefaultState().with(net.minecraft.block.FluidBlock.LEVEL, 8)); // falling water
			}
		}
	}

	/** Never climb (or swim) up beside a waterfall: the water drags you back down. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20, batchId = "nav_waterfall")
	public void noClimbingBesideWaterfalls(TestContext ctx) {
		ladderPit(ctx, false);
		Terrain terrain = new Terrain(ctx.getWorld());
		BlockPos start = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
		Goal top = Goal.near(ctx.getAbsolutePos(new BlockPos(1, 11, 1)), 0.5);
		TickBudget.begin(1000);
		PathFinder dry = new PathFinder(terrain, start, top, PathFinder.Options.basic(5000, 32), new LongOpenHashSet());
		ctx.assertTrue(dry.step(100000) == PathFinder.Status.FOUND, "can't climb a plain ladder");
		ladderPit(ctx, true);
		terrain = new Terrain(ctx.getWorld());
		TickBudget.begin(1000);
		PathFinder wet = new PathFinder(terrain, start, top, PathFinder.Options.basic(5000, 32), new LongOpenHashSet());
		ctx.assertTrue(wet.step(100000) != PathFinder.Status.FOUND, "climbed up beside a waterfall");
		ctx.complete();
	}

	/** A drowned under water isn't something to swim out and fight. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40, batchId = "drowned_ignored")
	public void leavesDrownedInTheWater(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtShore");
		stoneArena(ctx, 10);
		for (int x = 5; x <= 9; x++) {
			for (int z = 0; z <= 9; z++) {
				ctx.setBlockState(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 3; y++) {
					ctx.setBlockState(x, y, z, Blocks.WATER);
				}
			}
		}
		for (int x = 5; x <= 9; x++) {
			for (int z = 0; z <= 9; z++) {
				ctx.setBlockState(x, 0, z, Blocks.WATER); // a deeper pool: floor one lower
				ctx.setBlockState(x, -1, z, Blocks.STONE);
			}
		}
		ctx.assertTrue(m.spawn("GtShore", "kai", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(1.5, 1, 4.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtShore");
		com.botcraft.chat.CommandTasks.stay(c);
		var drowned = ctx.spawnEntity(net.minecraft.entity.EntityType.DROWNED, new BlockPos(7, 0, 4));
		drowned.setAiDisabled(true);
		drowned.setPersistent();
		ctx.waitAndRun(10, () -> {
			ctx.assertTrue(drowned.isSubmergedInWater(), "drowned should be under water");
			ctx.assertTrue(c.perception().threats().stream().noneMatch(t -> t == drowned), "treats a submerged drowned as a fight");
			drowned.discard();
			cleanup(m, "GtShore");
			ctx.complete();
		});
	}
}
