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

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (Inventories.count(p.getInventory(), s -> s.isOf(item)) == 0) {
			return placed ? Status.SUCCESS : fail("I don't have a " + ItemNames.display(item));
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
