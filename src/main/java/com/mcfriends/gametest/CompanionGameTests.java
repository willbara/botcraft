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
}
