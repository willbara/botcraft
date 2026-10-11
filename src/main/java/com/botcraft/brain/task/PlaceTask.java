package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.block.BedBlock;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Put a block item down near a spot (a bed at home, a chest...). Beds are remembered. */
public class PlaceTask extends Task {
	private final Item item;
	private final BlockPos near;
	private int attempts;
	private boolean placed;
	/** Spots that didn't work (a bed's other half had no room). */
	private final java.util.Set<BlockPos> tried = new java.util.HashSet<>();
	/** A bed going into the base's bed row: its slot, and failed tries at it. */
	@org.jetbrains.annotations.Nullable
	private com.botcraft.colony.Zones.BedSlot slot;
	private boolean slotChecked;
	private int slotTries;

	public PlaceTask(Companion c, Item item, BlockPos near) {
		super(c);
		this.item = item;
		this.near = near.toImmutable();
	}

	@Override
	public String name() {
		return "place_" + ItemNames.id(item);
	}

	@Override
	public String describe() {
		return "setting up a " + ItemNames.display(item);
	}

	public Item item() {
		return item;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 90;
	}

	/**
	 * Put the bed in its slot in the bed row: stand within reach of the foot block, face the way
	 * the bed should point, place. Null to fall back to anywhere near (the slot didn't work out).
	 */
	@org.jetbrains.annotations.Nullable
	private Status placeInSlot(CompanionPlayer p) {
		var world = p.getServerWorld();
		if (!world.getBlockState(slot.foot()).isReplaceable() || !world.getBlockState(slot.head()).isReplaceable() || slotTries > 12) {
			AiLog.log(c.name(), "bed slot at {} didn't work out, putting the bed anywhere near", slot.foot().toShortString());
			slot = null;
			return null;
		}
		if (!c.actions().canReachBlock(slot.foot().down()) || p.getBlockPos().equals(slot.foot()) || p.getBlockPos().equals(slot.head())) {
			// Stand beside the foot, not in the slot itself.
			c.nav().moveTo(Goal.reach(slot.foot(), 3.5), false);
			if (c.nav().state() == Navigator.State.FAILED) {
				slotTries += 4;
			}
			return Status.RUNNING;
		}
		c.nav().stop();
		p.setYaw(slot.facing().asRotation());
		if (c.actions().placeBlock(slot.foot(), s -> s.isOf(item))) {
			placed = true;
			var state = world.getBlockState(slot.head());
			if (state.isIn(BlockTags.BEDS)) {
				c.memory().setPlace("bed", slot.head(), c.dimension(), world.getTime());
			}
			AiLog.log(c.name(), "placed {} in the bed row at {}", ItemNames.id(item), slot.foot().toShortString());
			return Status.SUCCESS;
		}
		slotTries++;
		return Status.RUNNING;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (Inventories.count(p.getInventory(), s -> s.isOf(item)) == 0) {
			return placed ? Status.SUCCESS : fail("I don't have a " + ItemNames.display(item));
		}
		if (!slotChecked) {
			slotChecked = true;
			var colony = com.botcraft.colony.Colony.get();
			if (colony != null && com.botcraft.util.Experiments.on("zonePlacement") && new net.minecraft.item.ItemStack(item).isIn(net.minecraft.registry.tag.ItemTags.BEDS)
				&& colony.near(p.getServerWorld(), near, com.botcraft.colony.Colony.BASE_RADIUS)) {
				slot = com.botcraft.colony.Zones.freeBedSlot(colony, p.getServerWorld(), p.getBlockPos());
			}
		}
		if (slot != null) {
			Status s = placeInSlot(p);
			if (s != null) {
				return s;
			}
		}
		if (p.getBlockPos().getSquaredDistance(near) > 3 * 3) {
			c.nav().moveTo(Goal.near(near, 2.0), false);
			return c.nav().state() == Navigator.State.FAILED ? fail("I can't get there") : Status.RUNNING;
		}
		c.nav().stop();
		BlockPos spot = Spots.placeSpot(c, tried);
		if (spot == null || ++attempts > 40) {
			return fail("there's no room for it here");
		}
		// Beds extend in the direction we face: face away from ourselves through the spot.
		Direction facing = Direction.getFacing(spot.getX() - p.getX(), 0, spot.getZ() - p.getZ());
		if (new net.minecraft.item.ItemStack(item).isIn(net.minecraft.registry.tag.ItemTags.BEDS)) {
			BlockPos foot = spot.offset(facing);
			if (!p.getWorld().getBlockState(foot).isReplaceable() || !p.getWorld().getFluidState(foot).isEmpty()
				|| !p.getWorld().getBlockState(foot.down()).isSideSolidFullSquare(p.getWorld(), foot.down(), Direction.UP)
				|| p.getBoundingBox().intersects(new net.minecraft.util.math.Box(foot))) {
				tried.add(spot); // no room for the other half this way round
				return Status.RUNNING;
			}
			c.actions().lookAt(net.minecraft.util.math.Vec3d.ofCenter(spot.offset(facing)));
			p.setYaw(facing.asRotation());
		}
		if (c.actions().placeBlock(spot, s -> s.isOf(item))) {
			placed = true;
			var state = p.getWorld().getBlockState(spot);
			if (state.isIn(BlockTags.BEDS)) {
				BlockPos head = state.get(BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD ? spot : spot.offset(state.get(BedBlock.FACING));
				c.memory().setPlace("bed", head, c.dimension(), p.getWorld().getTime());
			}
			AiLog.log(c.name(), "placed {} at {}", ItemNames.id(item), spot.toShortString());
			return Status.SUCCESS;
		}
		return Status.RUNNING;
	}
}
