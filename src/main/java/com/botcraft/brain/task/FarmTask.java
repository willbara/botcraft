package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Colony;
import com.botcraft.colony.Zones;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Look after the base's farm (its farm zone, see {@link Zones}): harvest ripe crops, plant
 * seeds, carrots or potatoes on empty farmland, till the zone's grass and dirt when there are
 * seeds to plant, and pull up grass in and around the farm for seeds when there are none.
 * Drops are picked up like any other (the job queue collects leftovers). Done when there's
 * nothing left to do, or after {@code harvestGoal} crops if one was given.
 */
public class FarmTask extends Task {
	/** What to plant, best first: food straight away, then wheat (bread), then beetroot. */
	public static final List<Item> SEEDS = List.of(Items.POTATO, Items.CARROT, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS);
	private static final Predicate<ItemStack> SEED = s -> SEEDS.contains(s.getItem());
	private static final Predicate<ItemStack> HOE = s -> s.getItem() instanceof HoeItem;
	/** How far around the farm to look for grass to pull up for seeds. */
	private static final int GRASS_MARGIN = 8;

	enum Work {
		HARVEST, PLANT, CLEAR, TILL
	}

	/** One thing to do: where and what. {@code ground} is the farmland or soil, {@code above} the crop's space. */
	record Job(Work work, BlockPos ground, BlockPos above) {
		BlockPos target() {
			return work == Work.HARVEST || work == Work.CLEAR ? above : ground;
		}
	}

	/** What the farm needs (for deciding whether to go farming at all). */
	public record Needs(int ripe, int empty, int tillable, int grass) {
		/** Anything worth a trip, given whether we have seeds and a hoe. */
		public boolean worthIt(boolean seeds, boolean hoe) {
			return ripe > 0 || (seeds && empty > 0) || (seeds && hoe && tillable > 0) || (!seeds && empty + tillable > 0 && grass > 0);
		}
	}

	private final int harvestGoal;
	@Nullable
	private Job job;
	private final Set<BlockPos> skipped = new HashSet<>();
	private int jobTicks;
	private int harvested;
	private int planted;
	private int tilled;
	private boolean askedForHoe;

	public FarmTask(Companion c, int harvestGoal) {
		super(c);
		this.harvestGoal = harvestGoal;
	}

	@Override
	public String name() {
		return "farm";
	}

	@Override
	public String describe() {
		return "tending the farm (" + harvested + " harvested, " + planted + " planted)";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 5;
	}

	public int harvested() {
		return harvested;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		Colony colony = Colony.get();
		Colony.Zone farm = colony == null ? null : colony.zone(Zones.FARM);
		if (farm == null || colony.world() != p.getServerWorld()) {
			return fail("we don't have a farm yet");
		}
		if (harvestGoal > 0 && harvested >= harvestGoal) {
			return done();
		}
		ServerWorld world = p.getServerWorld();
		if (job == null || !stillToDo(world, job)) {
			job = next(world, farm, p);
			jobTicks = 0;
			if (job == null) {
				// Nothing to plant with and soil to till: a hoe first (once), then back to this.
				if (!askedForHoe && !hasHoe(p) && count(p, SEED) > 0 && needs(world, farm).tillable() > 0) {
					askedForHoe = true;
					return deferTo(List.of("craft wooden_hoe 1"));
				}
				return done();
			}
		}
		jobTicks++;
		BlockPos target = job.target();
		if (!c.actions().canReachBlock(target)) {
			if (jobTicks == 1 || c.nav().state() == Navigator.State.IDLE) {
				c.nav().moveTo(Goal.reachVisible(world, target, ActionController.BLOCK_REACH - 1.0), false);
			}
			if (c.nav().state() == Navigator.State.FAILED || jobTicks > 20 * 20) {
				skip();
			}
			return Status.RUNNING;
		}
		c.nav().stop();
		switch (job.work()) {
			case HARVEST, CLEAR -> {
				ActionController.MineResult r = c.actions().mineBlock(target);
				if (r == ActionController.MineResult.DONE) {
					if (job.work() == Work.HARVEST) {
						harvested++;
						c.memory().count("crops_harvested", 1);
					}
					job = null;
				} else if (r == ActionController.MineResult.FAILED) {
					skip();
				}
			}
			case TILL -> {
				if (c.actions().useItemOn(job.ground(), Direction.UP, HOE)) {
					tilled++;
					job = null;
				} else if (jobTicks > 40) {
					skip();
				}
			}
			case PLANT -> {
				if (c.actions().useItemOn(job.ground(), Direction.UP, bestSeed(p.getInventory()))) {
					planted++;
					c.memory().count("crops_planted", 1);
					job = null;
				} else if (jobTicks > 40) {
					skip();
				}
			}
		}
		return Status.RUNNING;
	}

	private void skip() {
		if (job != null) {
			skipped.add(job.target());
		}
		job = null;
		c.nav().stop();
	}

	private Status done() {
		c.nav().stop();
		c.actions().abortMining();
		AiLog.log(c.name(), "farm: harvested {}, planted {}, tilled {}", harvested, planted, tilled);
		return harvested + planted + tilled == 0 ? quietSuccess() : Status.SUCCESS;
	}

	/** Is this still worth doing (nobody else got to it first)? */
	private boolean stillToDo(ServerWorld w, Job j) {
		BlockState ground = w.getBlockState(j.ground());
		BlockState above = w.getBlockState(j.above());
		return switch (j.work()) {
			case HARVEST -> above.getBlock() instanceof CropBlock crop && crop.isMature(above);
			case PLANT -> ground.isOf(Blocks.FARMLAND) && above.isAir();
			case CLEAR -> isGrass(above);
			case TILL -> tillable(ground) && above.isAir();
		};
	}

	/** The nearest thing to do, most useful first: harvest, plant, then (for seeds) pull grass, then till. */
	@Nullable
	private Job next(ServerWorld w, Colony.Zone farm, CompanionPlayer p) {
		boolean seeds = count(p, SEED) > 0;
		boolean hoe = hasHoe(p);
		List<Job> harvest = new ArrayList<>();
		List<Job> plant = new ArrayList<>();
		List<Job> till = new ArrayList<>();
		List<Job> clear = new ArrayList<>();
		for (int x = farm.x1; x <= farm.x2; x++) {
			for (int z = farm.z1; z <= farm.z2; z++) {
				Job j = look(w, x, z);
				if (j == null || skipped.contains(j.target())) {
					continue;
				}
				switch (j.work()) {
					case HARVEST -> harvest.add(j);
					case PLANT -> plant.add(j);
					case TILL -> till.add(j);
					case CLEAR -> clear.add(j);
				}
			}
		}
		Job best = nearest(harvest, p);
		if (best == null && seeds) {
			best = nearest(plant, p);
		}
		if (best == null && seeds && hoe) {
			best = nearest(till, p);
		}
		if (best == null && (!seeds || !clear.isEmpty())) {
			// Grass in the farm is in the way; grass around it is where seeds come from.
			if (!seeds) {
				clear.addAll(grassAround(w, farm));
			}
			clear.removeIf(j -> skipped.contains(j.target()));
			best = nearest(clear, p);
		}
		return best;
	}

	/** What a farm column needs, if anything. */
	@Nullable
	private static Job look(ServerWorld w, int x, int z) {
		int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos above = new BlockPos(x, top, z);
		BlockPos ground = above.down();
		BlockState g = w.getBlockState(ground);
		BlockState a = w.getBlockState(above);
		if (a.getBlock() instanceof CropBlock crop) {
			return crop.isMature(a) ? new Job(Work.HARVEST, ground, above) : null;
		}
		if (isGrass(a)) {
			return new Job(Work.CLEAR, ground, above);
		}
		if (!a.isAir()) {
			return null;
		}
		if (g.isOf(Blocks.FARMLAND)) {
			return new Job(Work.PLANT, ground, above);
		}
		if (tillable(g)) {
			return new Job(Work.TILL, ground, above);
		}
		return null;
	}

	/** Grass plants within a few blocks of the farm (pulling them up drops seeds now and then). */
	private static List<Job> grassAround(ServerWorld w, Colony.Zone farm) {
		List<Job> out = new ArrayList<>();
		for (int x = farm.x1 - GRASS_MARGIN; x <= farm.x2 + GRASS_MARGIN; x++) {
			for (int z = farm.z1 - GRASS_MARGIN; z <= farm.z2 + GRASS_MARGIN; z++) {
				if (farm.contains(x, z) || !w.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos above = new BlockPos(x, top, z);
				if (isGrass(w.getBlockState(above))) {
					out.add(new Job(Work.CLEAR, above.down(), above));
				}
			}
		}
		return out;
	}

	/** What the farm zone needs right now (cheap: one look per column). */
	public static Needs needs(ServerWorld w, Colony.Zone farm) {
		int ripe = 0;
		int empty = 0;
		int tillable = 0;
		int grass = 0;
		for (int x = farm.x1; x <= farm.x2; x++) {
			for (int z = farm.z1; z <= farm.z2; z++) {
				if (!w.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				Job j = look(w, x, z);
				if (j == null) {
					continue;
				}
				switch (j.work()) {
					case HARVEST -> ripe++;
					case PLANT -> empty++;
					case TILL -> tillable++;
					case CLEAR -> grass++;
				}
			}
		}
		return new Needs(ripe, empty, tillable, grass);
	}

	private static boolean tillable(BlockState s) {
		return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.DIRT_PATH);
	}

	/** Short grass, tall grass and ferns (seeds drop from them). */
	static boolean isGrass(BlockState s) {
		return s.isOf(Blocks.GRASS) || s.isOf(Blocks.TALL_GRASS) || s.isOf(Blocks.FERN) || s.isOf(Blocks.LARGE_FERN);
	}

	@Nullable
	private static Job nearest(List<Job> jobs, CompanionPlayer p) {
		Job best = null;
		double bestD = Double.MAX_VALUE;
		for (Job j : jobs) {
			double d = j.target().getSquaredDistance(p.getBlockPos());
			if (d < bestD) {
				bestD = d;
				best = j;
			}
		}
		return best;
	}

	/** The seed to plant: food crops first. */
	private static Predicate<ItemStack> bestSeed(PlayerInventory inv) {
		for (Item it : SEEDS) {
			if (Inventories.count(inv, s -> s.isOf(it)) > 0) {
				return s -> s.isOf(it);
			}
		}
		return SEED;
	}

	private static int count(CompanionPlayer p, Predicate<ItemStack> which) {
		return Inventories.count(p.getInventory(), which);
	}

	public static boolean hasHoe(CompanionPlayer p) {
		return Inventories.find(p.getInventory(), HOE) >= 0;
	}

	/** Seeds or seed crops carried. */
	public static int seeds(CompanionPlayer p) {
		return count(p, SEED);
	}
}
