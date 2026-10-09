package com.mcfriends.gametest;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.entity.CompanionPlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.Direction;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.PathFinder;
import com.mcfriends.nav.Terrain;
import com.mcfriends.util.TickBudget;
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
		com.mcfriends.config.CompanionConfig.get().maxCompanions = Math.max(64, com.mcfriends.config.CompanionConfig.get().maxCompanions);
		com.mcfriends.config.CompanionConfig.get().initiative = 0; // tests want only the behaviour under test
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
		boolean jumped = pf.result().stream().anyMatch(s -> s.type() == com.mcfriends.nav.MoveType.JUMP);
		boolean door = pf.result().stream().anyMatch(s -> s.pos().equals(ctx.getAbsolutePos(new BlockPos(8, 1, 2))));
		ctx.assertTrue(jumped, "path should jump onto the step");
		ctx.assertTrue(door, "path should go through the door");
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
		com.mcfriends.chat.CommandTasks.stay(owner);
		follower.data.owner = owner.player().getUuid();
		follower.data.ownerName = "GtOwner";
		com.mcfriends.chat.CommandTasks.follow(follower, null);
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
		com.mcfriends.chat.CommandTasks.stay(c);
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.gather(c, "logs", 3, null) == null, "gather order");
		ctx.addInstantFinalTask(() -> {
			int logs = com.mcfriends.action.Inventories.count(c.player().getInventory(), com.mcfriends.action.Inventories::isLog);
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
		com.mcfriends.chat.CommandTasks.stay(c);
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
		com.mcfriends.chat.CommandTasks.stay(c);
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
		com.mcfriends.chat.CommandTasks.stay(c);
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
		com.mcfriends.chat.CommandTasks.stay(boss);
		com.mcfriends.chat.CommandTasks.stay(talker);
		talker.data.owner = boss.player().getUuid();
		talker.data.ownerName = "GtBoss";
		talker.data.chattiness = 1.0F;
		var parsed = com.mcfriends.chat.IntentParser.parse("GtTalker, follow me!", java.util.List.of("GtTalker", "GtBoss"));
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
		com.mcfriends.chat.CommandTasks.stay(c);
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
		com.mcfriends.chat.CommandTasks.stay(c);
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
		return com.mcfriends.action.Inventories.count(c.player().getInventory(), s -> s.isOf(item));
	}

	/** Milestone 8: craft a wooden pickaxe from 3 logs: planks, sticks, a crafting table placed and picked back up. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 800, batchId = "craft")
	public void craftsPickaxeFromLogs(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtCrafter");
		flatArena(ctx, 7);
		ctx.assertTrue(m.spawn("GtCrafter", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(3.5, 1, 3.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtCrafter");
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.BIRCH_LOG, 3));
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "craft a wooden pickaxe", null) == null, "order");
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
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_LOG, 4));
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "mine 3 stone", null) == null, "order");
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
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE));
		c.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.SPRUCE_LOG));
		var parsed = com.mcfriends.chat.IntentParser.parse("GtDigger, mine 1 coal and then craft a torch", java.util.List.of("GtDigger"));
		ctx.assertTrue(parsed.intents().size() == 2, "intents " + parsed.intents());
		ctx.assertTrue(com.mcfriends.chat.ChatRouter.respond(c, null, parsed.intents(), false) == null, "respond");
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
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.gather(c, "logs", 2, null) == null, "order");
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
		com.mcfriends.chat.CommandTasks.stay(o);
		f.player().getInventory().clear();
		f.data.owner = o.player().getUuid();
		f.data.ownerName = owner;
		com.mcfriends.chat.CommandTasks.follow(f, null);
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
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.RAW_IRON, 3);
		give(c, net.minecraft.item.Items.OAK_LOG, 2);
		give(c, net.minecraft.item.Items.COBBLESTONE, 8);
		give(c, net.minecraft.item.Items.COAL, 2);
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "craft an iron pickaxe", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			ctx.assertTrue(count(c, net.minecraft.item.Items.IRON_PICKAXE) == 1, "no iron pickaxe; " + c.describeActivity()
				+ "; queued " + c.jobs().pending() + "; inv " + c.player().getInventory().main.stream().filter(st -> !st.isEmpty()).toList());
			cleanup(m, "GtSmith");
		});
	}

	/** Mechanics: hunt a cow (only when there are more than two) and cook the beef. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2000, batchId = "hunt_cook")
	public void huntsAndCooks(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtHunter");
		flatArena(ctx, 12);
		for (int i = 0; i < 4; i++) {
			ctx.spawnEntity(net.minecraft.entity.EntityType.COW, new BlockPos(8 + (i % 2), 1, 4 + i * 2)).setPersistent();
		}
		ctx.assertTrue(m.spawn("GtHunter", "zuri", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 6.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtHunter");
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.STONE_SWORD, 1);
		give(c, net.minecraft.item.Items.FURNACE, 1);
		give(c, net.minecraft.item.Items.COAL, 1);
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "kill 1 cow and then cook the beef", null) == null, "order");
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

	/** Farming: harvests ripe wheat (leaves unripe), collects, replants from the dropped seeds. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "farm_harvest")
	public void harvestsAndReplants(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtFarmer");
		grassArena(ctx, 10);
		BlockState farmland = Blocks.FARMLAND.getDefaultState().with(net.minecraft.block.FarmlandBlock.MOISTURE, 7);
		for (int x = 3; x <= 5; x++) {
			for (int z = 3; z <= 5; z++) {
				ctx.setBlockState(x, 0, z, farmland);
				boolean ripe = !(x == 5 && z >= 4);
				ctx.setBlockState(x, 1, z, ((net.minecraft.block.CropBlock) Blocks.WHEAT).withAge(ripe ? 7 : 2));
			}
		}
		ctx.setBlockState(4, 0, 7, Blocks.WATER);
		ctx.assertTrue(m.spawn("GtFarmer", "makena", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 1.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtFarmer");
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "harvest and replant the farm", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			int wheat = count(c, net.minecraft.item.Items.WHEAT);
			int crops = 0;
			StringBuilder tiles = new StringBuilder();
			for (int x = 3; x <= 5; x++) {
				for (int z = 3; z <= 5; z++) {
					BlockPos at = ctx.getAbsolutePos(new BlockPos(x, 1, z));
					boolean crop = ctx.getWorld().getBlockState(at).isOf(Blocks.WHEAT);
					crops += crop ? 1 : 0;
					tiles.append(crop ? "W" : ctx.getWorld().getBlockState(at.down()).isOf(Blocks.FARMLAND) ? "F" : "D");
				}
			}
			ctx.assertTrue(wheat >= 7 && crops == 9 && c.jobs().isIdle(), "wheat " + wheat + ", tiles " + tiles + "; seeds "
				+ count(c, net.minecraft.item.Items.WHEAT_SEEDS) + "; " + c.describeActivity());
			cleanup(m, "GtFarmer");
		});
	}

	/** Farming: "make a 3x3 farm" clears grass, tills with the hoe and plants all nine seeds. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600, batchId = "farm_make")
	public void makesFarm(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtTiller");
		grassArena(ctx, 10);
		ctx.setBlockState(4, 1, 4, Blocks.GRASS);
		ctx.setBlockState(5, 1, 5, Blocks.GRASS);
		ctx.assertTrue(m.spawn("GtTiller", "noor", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(4.5, 1, 2.5)), 0.0F) == null, "spawn");
		Companion c = m.byName("GtTiller");
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		give(c, net.minecraft.item.Items.WOODEN_HOE, 1);
		give(c, net.minecraft.item.Items.WHEAT_SEEDS, 9);
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "make a 3x3 farm", null) == null, "order");
		ctx.addInstantFinalTask(() -> {
			int planted = 0;
			for (int x = -1; x <= 10; x++) {
				for (int z = -1; z <= 10; z++) {
					BlockPos at = ctx.getAbsolutePos(new BlockPos(x, 1, z));
					if (ctx.getWorld().getBlockState(at).isOf(Blocks.WHEAT) && ctx.getWorld().getBlockState(at.down()).isOf(Blocks.FARMLAND)) {
						planted++;
					}
				}
			}
			ctx.assertTrue(planted == 9 && c.jobs().isIdle(), "planted " + planted + "; " + c.describeActivity());
			cleanup(m, "GtTiller");
		});
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
		ctx.assertTrue(com.mcfriends.chat.CommandTasks.apply(c, "do your own thing", null) == null, "order");
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
		com.mcfriends.chat.CommandTasks.stay(a);
		com.mcfriends.chat.CommandTasks.stay(b);
		BlockPos iron = ctx.getAbsolutePos(new BlockPos(1, -20, 1));
		a.memory().sight("iron", iron, a.dimension(), 0);
		ctx.runAtTick(60, () -> com.mcfriends.chat.Social.banter(a, b, "found"));
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
		com.mcfriends.chat.CommandTasks.stay(c);
		c.player().getInventory().clear();
		c.brain().setJob(new com.mcfriends.brain.task.ShelterTask(c, false).asJob("shelter"));
		int ground = ctx.getAbsolutePos(new BlockPos(0, 4, 0)).getY();
		ctx.addInstantFinalTask(() -> {
			BlockPos feet = com.mcfriends.nav.Navigator.feet(c.player());
			ctx.assertTrue(feet.getY() <= ground - 3, "not dug in: feet " + feet.toShortString() + " ground " + ground);
			ctx.assertTrue(!ctx.getWorld().getBlockState(feet.up(2)).isAir(), "no roof over " + feet.toShortString());
			cleanup(m, "GtMole");
		});
	}
}
