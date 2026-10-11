package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.BreakPolicy;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.util.math.BlockPos;

/**
 * The classic first night: dig three blocks down into natural ground (so the roof sits inside
 * the hole, between solid walls), put a block over our head,
 * and wait for morning. When it's day the roof comes off and the navigator climbs/digs out.
 */
public class ShelterTask extends Task {
	private enum Phase { DIG, ROOF, WAIT }

	private Phase phase = Phase.DIG;
	private BlockPos hole;
	private int dug;
	private BlockPos digging;

	private final boolean untilMorning;

	public ShelterTask(Companion c) {
		this(c, true);
	}

	/** @param untilMorning false = keep sheltering even in daylight (tests) */
	public ShelterTask(Companion c, boolean untilMorning) {
		super(c);
		this.untilMorning = untilMorning;
	}

	@Override
	public String name() {
		return "shelter";
	}

	@Override
	public String describe() {
		return phase == Phase.WAIT ? "hiding out until morning" : "digging in for the night";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 12;
	}

	@Override
	protected void onStart(boolean resumed) {
		if (hole == null) {
			hole = Navigator.feet(c.player());
		}
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		var world = p.getServerWorld();
		if (untilMorning && p.getWorld().isDay() && ticks() > 40) {
			// Morning: open the roof and we're done (following/working paths will climb out).
			BlockPos roof = Navigator.feet(p).up(2);
			if (!world.getBlockState(roof).isAir() && BreakPolicy.isNaturalTerrain(world.getBlockState(roof))
				|| Inventories.isThrowaway(new net.minecraft.item.ItemStack(world.getBlockState(roof).getBlock().asItem()))) {
				if (c.actions().mineBlock(roof) != ActionController.MineResult.IN_PROGRESS) {
					return Status.SUCCESS;
				}
				return Status.RUNNING;
			}
			return Status.SUCCESS;
		}
		switch (phase) {
			case DIG -> {
				if (dug >= 3) {
					phase = Phase.ROOF;
					return Status.RUNNING;
				}
				if (digging != null) {
					if (world.getBlockState(digging).isAir()) {
						dug++; // that block is gone; we drop into the hole
						digging = null;
					} else {
						c.actions().mineBlock(digging);
					}
					return Status.RUNNING;
				}
				if (!p.isOnGround()) {
					return Status.RUNNING; // still dropping into the hole
				}
				BlockPos below = Navigator.feet(p).down();
				var state = world.getBlockState(below);
				var under = world.getBlockState(below.down());
				if (!BreakPolicy.canBreakForNav(world, below, state, true) || under.isAir() || !under.getFluidState().isEmpty()) {
					// Can't dig here (rock we may not break, a cave below...): just roof over where we stand.
					phase = Phase.ROOF;
					return Status.RUNNING;
				}
				c.actions().moveTo(net.minecraft.util.math.Vec3d.ofBottomCenter(Navigator.feet(p)), 0.3);
				digging = below.toImmutable();
				c.actions().mineBlock(digging);
			}
			case ROOF -> {
				BlockPos roof = Navigator.feet(p).up(2);
				if (!world.getBlockState(roof).isReplaceable()) {
					AiLog.log(c.name(), "sheltered at {}", Navigator.feet(p).toShortString());
					phase = Phase.WAIT;
					return Status.RUNNING;
				}
				if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0) {
					AiLog.log(c.name(), "shelter: nothing to roof with (inv {})", p.getInventory().main.stream().filter(st -> !st.isEmpty()).toList());
					phase = Phase.WAIT; // nothing to roof with; the hole still helps a bit
					return Status.RUNNING;
				}
				c.actions().lookAt(net.minecraft.util.math.Vec3d.ofCenter(roof));
				if (c.actions().placeBlock(roof, Inventories::isThrowaway)) {
					BreakPolicy.recordPlaced(world, roof);
				}
			}
			case WAIT -> {
				// Sit tight. Fights still happen (interrupts) if something gets in.
			}
		}
		return Status.RUNNING;
	}
}
