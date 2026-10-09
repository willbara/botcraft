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
		PathFinder pf = new PathFinder(terrain, start, Goal.near(end, 0.5), new PathFinder.Options(false, false, 5000, 64), new LongOpenHashSet());
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

	/** Milestone 4: a brave companion picks its best weapon and kills a zombie. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600, batchId = "survive_fight")
	public void fightsZombie(TestContext ctx) {
		CompanionManager m = manager(ctx);
		cleanup(m, "GtFighter");
		flatArena(ctx, 10);
		ctx.assertTrue(m.spawn("GtFighter", "zuri", null, ctx.getWorld(), ctx.getAbsolute(new Vec3d(2.5, 1, 5.5)), -90.0F) == null, "spawn");
		Companion c = m.byName("GtFighter");
		c.data.bravery = 1.0F;
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
}
