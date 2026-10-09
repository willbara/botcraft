package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.Crops;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harvest the mature crops around a spot and replant each one with the matching seed, like a
 * player tending their farm. Unripe crops are left alone.
 */
public class HarvestTask extends Task {
	private enum Phase { HARVEST, COLLECT, REPLANT }

	private final BlockPos center;
	private final int radius;
	private Phase phase = Phase.HARVEST;
	private int phaseTicks;
	private int harvested;
	private int replanted;
	/** Spot -> seed that grew there, waiting to be replanted. */
	private final Map<BlockPos, Item> toReplant = new LinkedHashMap<>();
	private final Set<BlockPos> bad = new HashSet<>();
	@Nullable
	private BlockPos target;
	@Nullable
	private ItemEntity collecting;
	private final Pickup pickup = new Pickup();

	public HarvestTask(Companion c, BlockPos center, int radius) {
		super(c);
		this.center = center.toImmutable();
		this.radius = radius;
	}

	@Override
	public String name() {
		return "harvest";
	}

	@Override
	public String describe() {
		return "harvesting the farm (" + harvested + " picked, " + replanted + " replanted)";
	}

	public int harvested() {
		return harvested;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 6;
	}

	private void setPhase(Phase p) {
		phase = p;
		phaseTicks = 0;
		target = null;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		phaseTicks++;
		return switch (phase) {
			case HARVEST -> harvest(p);
			case COLLECT -> collect(p);
			case REPLANT -> replant(p);
		};
	}

	private List<BlockPos> matureCrops(CompanionPlayer p) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(center.add(-radius, -3, -radius), center.add(radius, 3, radius))) {
			BlockState s = p.getWorld().getBlockState(pos);
			if (Crops.isCrop(s) && Crops.isMature(s) && !bad.contains(pos)) {
				out.add(pos.toImmutable());
			}
		}
		BlockPos me = p.getBlockPos();
		out.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(me)));
		return out;
	}

	private Status harvest(CompanionPlayer p) {
		if (target == null || !Crops.isMature(p.getWorld().getBlockState(target))) {
			List<BlockPos> crops = matureCrops(p);
			// Pick up and replant after every handful so drops don't despawn and rows stay tidy.
			if (crops.isEmpty() || toReplant.size() >= 8) {
				setPhase(Phase.COLLECT);
				return Status.RUNNING;
			}
			target = crops.get(0);
		}
		if (c.actions().canReachBlock(target)) {
			c.nav().stop();
			BlockState s = p.getWorld().getBlockState(target);
			ActionController.MineResult r = c.actions().mineBlock(target);
			if (r == ActionController.MineResult.DONE) {
				toReplant.put(target, Crops.SEED_FOR.get(s.getBlock()));
				harvested++;
				c.memory().count("harvested", 1);
				target = null;
			} else if (r == ActionController.MineResult.FAILED) {
				bad.add(target);
				target = null;
			}
		} else {
			c.nav().moveTo(Goal.reachVisible(p.getWorld(), target, ActionController.BLOCK_REACH - 0.5), false);
			if (c.nav().state() == Navigator.State.FAILED) {
				bad.add(target);
				target = null;
				c.nav().stop();
			}
		}
		return Status.RUNNING;
	}

	private Status collect(CompanionPlayer p) {
		if (collecting == null || !collecting.isAlive()) {
			List<ItemEntity> drops = p.getServerWorld().getEntitiesByClass(ItemEntity.class, new Box(center).expand(radius + 2, 4, radius + 2),
				e -> e.isAlive() && !pickup.ignored(e));
			drops.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
			collecting = drops.isEmpty() ? null : drops.get(0);
		}
		if (collecting == null || phaseTicks > 20 * 30) {
			collecting = null;
			setPhase(Phase.REPLANT);
			return Status.RUNNING;
		}
		if (!pickup.tick(c, collecting)) {
			collecting = null;
		}
		return Status.RUNNING;
	}

	private Status replant(CompanionPlayer p) {
		boolean hoe = com.mcfriends.action.Inventories.count(p.getInventory(), s -> s.getItem() instanceof net.minecraft.item.HoeItem) > 0;
		toReplant.entrySet().removeIf(e -> {
			boolean drop = !p.getWorld().getBlockState(e.getKey()).isAir()
				|| !(p.getWorld().getBlockState(e.getKey().down()).isOf(Blocks.FARMLAND)
				|| (hoe && Crops.tillable(p.getWorld().getBlockState(e.getKey().down()))));
			if (drop) {
				AiLog.log(c.name(), "harvest: not replanting {} (block {}, soil {})", e.getKey().toShortString(),
					p.getWorld().getBlockState(e.getKey()).getBlock(), p.getWorld().getBlockState(e.getKey().down()).getBlock());
			}
			return drop;
		});
		if (toReplant.isEmpty()) {
			if (!matureCrops(p).isEmpty()) {
				setPhase(Phase.HARVEST);
				return Status.RUNNING;
			}
			AiLog.log(c.name(), "harvest done: {} picked, {} replanted", harvested, replanted);
			return harvested > 0 ? Status.SUCCESS : fail("nothing's ripe yet");
		}
		Map.Entry<BlockPos, Item> next = toReplant.entrySet().iterator().next();
		BlockPos spot = next.getKey();
		Item seed = next.getValue();
		BlockPos soil = spot.down();
		if (com.mcfriends.action.Inventories.count(p.getInventory(), s -> s.isOf(seed)) == 0) {
			AiLog.log(c.name(), "harvest: out of {} for {}", seed, spot.toShortString());
			toReplant.remove(spot); // no seeds of this kind left
			return Status.RUNNING;
		}
		if (c.actions().canReachBlock(soil)) {
			c.nav().stop();
			if (Crops.tillable(p.getWorld().getBlockState(soil))) {
				c.actions().useItemOn(soil, Direction.UP, s -> s.getItem() instanceof net.minecraft.item.HoeItem); // trampled
				return Status.RUNNING;
			}
			if (c.actions().useItemOn(soil, Direction.UP, s -> s.isOf(seed))) {
				replanted++;
			} else {
				AiLog.log(c.name(), "harvest: couldn't replant at {} (soil {})", spot.toShortString(), p.getWorld().getBlockState(soil).getBlock());
			}
			toReplant.remove(spot);
		} else {
			c.nav().moveTo(Goal.reachVisible(p.getWorld(), soil, ActionController.BLOCK_REACH - 0.5), false);
			if (c.nav().state() == Navigator.State.FAILED || phaseTicks > 20 * 60) {
				toReplant.remove(spot);
				c.nav().stop();
			}
		}
		return Status.RUNNING;
	}
}
