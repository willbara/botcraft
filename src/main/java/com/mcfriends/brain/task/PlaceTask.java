package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.ItemNames;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
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
		BlockPos spot = Spots.placeSpot(c);
		if (spot == null || ++attempts > 40) {
			return fail("there's no room for it here");
		}
		// Beds extend in the direction we face: face away from ourselves through the spot.
		Direction facing = Direction.getFacing(spot.getX() - p.getX(), 0, spot.getZ() - p.getZ());
		if (new net.minecraft.item.ItemStack(item).isIn(net.minecraft.registry.tag.ItemTags.BEDS)) {
			if (!p.getWorld().getBlockState(spot.offset(facing)).isReplaceable()) {
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
