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
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Look after the base's farm (its farm plots, see {@link Zones}): harvest ripe crops, plant
 * seeds, carrots or potatoes on empty farmland, till the zone's grass and dirt when there are
 * seeds to plant, and pull up grass in and around the farm for seeds when there are none.
 * A plot whose soil isn't all within reach of water gets a water hole in its middle: fill a
 * bucket at the nearest water (making a bucket first if the base has the iron), dig out the
 * middle and pour it in. One block of water wets the whole 9x9 plot.
 * Drops are picked up like any other (the job queue collects leftovers). Done when there's
 * nothing left to do, or after {@code harvestGoal} crops if one was given.
 */
public class FarmTask extends Task {
	/** What to plant, best first: food straight away, then wheat (bread), then beetroot. */
	public static final List<Item> SEEDS = List.of(Items.POTATO, Items.CARROT, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS);
	private static final Predicate<ItemStack> SEED = s -> SEEDS.contains(s.getItem());
	private static final Predicate<ItemStack> HOE = s -> s.getItem() instanceof HoeItem;
	/** Per base: who's farming it, and until when (renewed every tick while they are). */
	private static final java.util.Map<String, String> FARMER = new java.util.HashMap<>();
	private static final java.util.Map<String, Long> FARMER_UNTIL = new java.util.HashMap<>();

	/** Someone else is tending this base's farm right now. */
	public static boolean someoneElseFarming(String colony, String name, long now) {
		String who = FARMER.get(colony);
		return who != null && !who.equals(name) && now < FARMER_UNTIL.getOrDefault(colony, 0L);
	}

	/** Ripe crops worth a trip to the farm (when not hungry). */
	public static final int RIPE_TRIP = 6;
	/** How far around the farm to look for grass to pull up for seeds. */
	private static final int GRASS_MARGIN = 8;
	/** How far to look for grass when there's none close by (a new farm in a wood, a cleared base). */
	private static final int WIDE_GRASS_MARGIN = 20;

	enum Work {
		HARVEST, PLANT, CLEAR, TILL
	}

	/** One thing to do: where and what. {@code ground} is the farmland or soil, {@code above} the crop's space. */
	record Job(Work work, BlockPos ground, BlockPos above) {
		BlockPos target() {
			return work == Work.HARVEST || work == Work.CLEAR ? above : ground;
		}
	}

	/** What the farm needs (for deciding whether to go farming at all). {@code dry}: plots that want water. */
	public record Needs(int ripe, int empty, int tillable, int grass, int dry, int wild) {
		public Needs(int ripe, int empty, int tillable, int grass) {
			this(ripe, empty, tillable, grass, 0, 0);
		}

		/**
		 * Worth a trip: a few ripe crops (any, when hungry), a few tiles to plant or till, or grass in
		 * the way. One ripe stalk isn't worth walking over for.
		 */
		public boolean worthIt(boolean seeds, boolean hoe, boolean hungry) {
			// (wild: grass growing round the plots, where a new farm's first seeds come from)
			return ripe >= (hungry ? 1 : RIPE_TRIP) || (seeds && empty >= 3) || (seeds && hoe && tillable >= 3) || grass >= 3
				|| (!seeds && empty + tillable > 0 && grass + wild > 0);
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
	private boolean askedForSeeds;
	/** The water hole being made, and the water we fill the bucket at. */
	@Nullable
	private BlockPos waterHole;
	@Nullable
	private BlockPos waterSource;
	private boolean noWater;
	private boolean askedForBucket;
	private int waterTicks;
	private static final Predicate<ItemStack> WATER_BUCKET = s -> s.isOf(Items.WATER_BUCKET);
	private static final Predicate<ItemStack> BUCKET = s -> s.isOf(Items.BUCKET);
	/** How far from the plot to look for water to fill a bucket at. */
	private static final int WATER_SEARCH = 40;

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
		List<Colony.Zone> farms = colony == null ? List.of() : Zones.farms(colony);
		if (farms.isEmpty() || colony.world() != p.getServerWorld()) {
			return fail("we don't have a farm yet");
		}
		if (harvestGoal > 0 && harvested >= harvestGoal) {
			return done();
		}
		ServerWorld world = p.getServerWorld();
		if (!askedForSeeds && count(p, SEED) == 0) {
			// Seeds (or potatoes, carrots) in the chests: take some before pulling up grass for them.
			askedForSeeds = true;
			for (Item seed : SEEDS) {
				String w = com.botcraft.brain.Jobs.withdrawSpec(c, com.botcraft.knowledge.ItemNames.id(seed), s -> s.isOf(seed), 32);
				if (w != null) {
					return deferTo(List.of(w));
				}
			}
		}
		FARMER.put(colony.id(), c.name());
		FARMER_UNTIL.put(colony.id(), c.now() + 20 * 15);
		if (job == null && !noWater) {
			Status s = water(colony, world, p);
			if (s != null) {
				return s;
			}
		}
		if (job == null || !stillToDo(world, job)) {
			job = next(world, colony, farms, p);
			jobTicks = 0;
			if (job == null) {
				// Nothing to plant with and soil to till: a hoe first (once), then back to this.
				if (!askedForHoe && !hasHoe(p) && count(p, SEED) > 0 && needs(world, colony).tillable() > 0) {
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
			case CLEAR -> inTheWay(above);
			case TILL -> tillable(ground) && above.isAir();
		};
	}

	// ---- water ------------------------------------------------------------------------------------

	/**
	 * Give a dry plot its water hole: get a water bucket, dig out the plot's middle, pour. Null when
	 * there's nothing to do about water (none needed, or no way to get any): get on with farming.
	 */
	@Nullable
	private Status water(Colony colony, ServerWorld w, CompanionPlayer p) {
		if (waterHole == null) {
			Colony.Zone dry = Zones.dryFarm(colony, w);
			if (dry == null) {
				noWater = true;
				return null;
			}
			waterHole = Zones.waterSpot(dry);
			waterTicks = 0;
		}
		if (++waterTicks > 20 * 120) {
			return giveUpWater("took too long");
		}
		BlockPos hole = waterHole;
		if (w.getFluidState(hole).isIn(FluidTags.WATER) && w.getFluidState(hole).isStill()) {
			AiLog.log(c.name(), "farm: watered the plot at {}", hole.toShortString());
			c.memory().count("farms_watered", 1);
			waterHole = null;
			return Status.RUNNING;
		}
		if (count(p, WATER_BUCKET) > 0) {
			// The crop (or grass) on top first, then the soil, then pour.
			for (BlockPos b : List.of(hole.up(), hole)) {
				BlockState s = w.getBlockState(b);
				if (!s.isAir() && !s.isReplaceable() || b.equals(hole.up()) && !s.isAir()) {
					if (!c.actions().canReachBlock(b)) {
						walkNear(w, b);
						return Status.RUNNING;
					}
					c.nav().stop();
					if (c.actions().mineBlock(b) == ActionController.MineResult.FAILED) {
						return giveUpWater("couldn't dig the hole");
					}
					return Status.RUNNING;
				}
			}
			if (!c.actions().canReachBlock(hole)) {
				walkNear(w, hole);
				return Status.RUNNING;
			}
			c.nav().stop();
			// Aim at the floor of the hole: the water goes in on top of it.
			c.actions().useItemToward(new Vec3d(hole.getX() + 0.5, hole.getY() + 0.02, hole.getZ() + 0.5), WATER_BUCKET);
			return Status.RUNNING;
		}
		if (count(p, BUCKET) > 0) {
			if (waterSource == null || !isSource(w, waterSource)) {
				waterSource = findWater(colony, w, hole);
				if (waterSource == null) {
					return giveUpWater("there's no water near the farm");
				}
			}
			if (!c.actions().canReachBlock(waterSource)) {
				walkNear(w, waterSource);
				return Status.RUNNING;
			}
			c.nav().stop();
			c.actions().useItemToward(new Vec3d(waterSource.getX() + 0.5, waterSource.getY() + 0.85, waterSource.getZ() + 0.5), BUCKET);
			return Status.RUNNING;
		}
		if (!askedForBucket && bucketToHand(colony, p)) {
			askedForBucket = true;
			return deferTo(List.of("craft bucket 1"));
		}
		return giveUpWater("no bucket, and no iron for one");
	}

	@Nullable
	private Status giveUpWater(String why) {
		AiLog.log(c.name(), "farm: no water for the plot at {}: {}", waterHole == null ? "?" : waterHole.toShortString(), why);
		noWater = true;
		waterHole = null;
		c.nav().stop();
		return null;
	}

	private void walkNear(ServerWorld w, BlockPos b) {
		if (c.nav().state() == Navigator.State.IDLE || c.nav().state() == Navigator.State.FAILED) {
			c.nav().moveTo(Goal.reachVisible(w, b, ActionController.BLOCK_REACH - 1.5), false);
		}
	}

	/** A bucket in the chests, or the iron to make one (ours and the chests'). */
	private static boolean bucketToHand(Colony colony, CompanionPlayer p) {
		int iron = count(p, s -> s.isOf(Items.IRON_INGOT)) + colony.count(s -> s.isOf(Items.IRON_INGOT));
		return colony.count(s -> s.isOf(Items.BUCKET)) > 0 || iron >= 3;
	}

	private static boolean isSource(ServerWorld w, BlockPos b) {
		var f = w.getFluidState(b);
		return f.isIn(FluidTags.WATER) && f.isStill();
	}

	/** The nearest open water source near the farm that isn't another plot's water hole. */
	@Nullable
	static BlockPos findWater(Colony colony, ServerWorld w, BlockPos near) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -WATER_SEARCH; dx <= WATER_SEARCH; dx++) {
			for (int dz = -WATER_SEARCH; dz <= WATER_SEARCH; dz++) {
				int x = near.getX() + dx;
				int z = near.getZ() + dz;
				if (!w.isChunkLoaded(x >> 4, z >> 4) || Zones.isWaterSpot(colony, x, z)) {
					continue;
				}
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				if (!isSource(w, m.set(x, top, z)) || !w.getBlockState(m.up()).isAir()) {
					continue;
				}
				double d = dx * dx + dz * dz;
				if (d < bestD) {
					bestD = d;
					best = m.toImmutable();
				}
			}
		}
		return best;
	}

	/** The nearest thing to do, most useful first: harvest, plant, then (for seeds) pull grass, then till. */
	@Nullable
	private Job next(ServerWorld w, Colony colony, List<Colony.Zone> farms, CompanionPlayer p) {
		boolean seeds = count(p, SEED) > 0;
		boolean hoe = hasHoe(p);
		List<Job> harvest = new ArrayList<>();
		List<Job> plant = new ArrayList<>();
		List<Job> till = new ArrayList<>();
		List<Job> clear = new ArrayList<>();
		for (Colony.Zone farm : farms) {
			for (int x = farm.x1; x <= farm.x2; x++) {
				for (int z = farm.z1; z <= farm.z2; z++) {
					Job j = Zones.isWaterSpot(colony, x, z) ? null : look(w, x, z);
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
				for (Colony.Zone farm : farms) {
					clear.addAll(grassAround(w, colony, farm, GRASS_MARGIN));
				}
				if (clear.stream().allMatch(j -> skipped.contains(j.target()))) {
					for (Colony.Zone farm : farms) {
						clear.addAll(grassAround(w, colony, farm, WIDE_GRASS_MARGIN));
					}
				}
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
		if (inTheWay(a)) {
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
	private static List<Job> grassAround(ServerWorld w, Colony colony, Colony.Zone farm, int margin) {
		List<Job> out = new ArrayList<>();
		for (int x = farm.x1 - margin; x <= farm.x2 + margin; x++) {
			for (int z = farm.z1 - margin; z <= farm.z2 + margin; z++) {
				if (Zones.at(colony, x, z) != null || !w.isChunkLoaded(x >> 4, z >> 4)) {
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

	/** What the base's farm plots need right now (cheap: one look per column). */
	public static Needs needs(ServerWorld w, Colony colony) {
		int ripe = 0;
		int empty = 0;
		int tillable = 0;
		int grass = 0;
		int dry = 0;
		for (Colony.Zone farm : Zones.farms(colony)) {
			for (int x = farm.x1; x <= farm.x2; x++) {
				for (int z = farm.z1; z <= farm.z2; z++) {
					if (!w.isChunkLoaded(x >> 4, z >> 4) || Zones.isWaterSpot(colony, x, z)) {
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
			if (Zones.needsWater(w, farm)) {
				dry++;
			}
		}
		int wild = 0;
		if (grass == 0 && empty + tillable > 0) {
			// Nothing to pull up in the plots: is there grass round them (for a new farm's seeds)?
			for (Colony.Zone farm : Zones.farms(colony)) {
				wild += grassAround(w, colony, farm, WIDE_GRASS_MARGIN).size();
				if (wild > 0) {
					break;
				}
			}
		}
		return new Needs(ripe, empty, tillable, grass, dry, wild);
	}

	/** Carrying a bucket (empty or full): a dry plot is worth the trip. */
	public static boolean hasBucket(CompanionPlayer p) {
		return count(p, s -> s.isOf(Items.BUCKET) || s.isOf(Items.WATER_BUCKET)) > 0;
	}

	private static boolean tillable(BlockState s) {
		return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.DIRT_PATH);
	}

	/** Plants in the farm zone that have to go before it can be tilled: grass, ferns, flowers. */
	static boolean inTheWay(BlockState s) {
		return isGrass(s) || s.isIn(net.minecraft.registry.tag.BlockTags.SMALL_FLOWERS) || s.isIn(net.minecraft.registry.tag.BlockTags.TALL_FLOWERS);
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

	/** Something to plant in the base's chests. */
	public static boolean seedsInStock(Colony colony) {
		return colony.count(SEED) > 0;
	}
}
