package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Lighting;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Colony;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Light up the base: walk to the darkest open ground near home (where monsters could spawn) and
 * put a torch there, until nothing within {@link #RADIUS} blocks is dark or we run out of torches.
 */
public class LightHomeTask extends Task {
	public static final int RADIUS = 12;
	private static final int MAX_TORCHES = 12;

	@Nullable
	private BlockPos target;
	private final Set<BlockPos> skipped = new HashSet<>();
	private int placed;
	private int targetTicks;

	public LightHomeTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "light_home";
	}

	@Override
	public String describe() {
		return "lighting up the base (" + placed + " torches)";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 180;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		BlockPos base = base(c);
		if (base == null) {
			return fail("there's no home to light");
		}
		if (Inventories.count(p.getInventory(), s -> s.isOf(Items.TORCH)) == 0) {
			return placed > 0 ? done() : fail("I don't have any torches");
		}
		if (placed >= MAX_TORCHES) {
			return done();
		}
		if (target == null || !p.getWorld().getBlockState(target).isAir()) {
			List<BlockPos> dark = Lighting.darkSpots(p.getServerWorld(), base, RADIUS);
			dark.removeIf(skipped::contains);
			if (dark.isEmpty()) {
				return done();
			}
			target = dark.get(0);
			targetTicks = 0;
		}
		targetTicks++;
		if (!c.actions().canReachBlock(target)) {
			if (targetTicks == 1 || c.nav().state() == Navigator.State.IDLE) {
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), target, 3.5), false);
			}
			if (c.nav().state() == Navigator.State.FAILED || targetTicks > 20 * 20) {
				skipped.add(target);
				target = null;
			}
			return Status.RUNNING;
		}
		c.nav().stop();
		if (c.actions().placeBlock(target, s -> s.isOf(Items.TORCH))) {
			placed++;
			target = null;
		} else if (targetTicks > 40) {
			skipped.add(target);
			target = null;
		}
		return Status.RUNNING;
	}

	private Status done() {
		c.nav().stop();
		AiLog.log(c.name(), "light home: placed {} torch(es)", placed);
		return Status.SUCCESS;
	}

	/** The colony base, else this companion's home. */
	@Nullable
	static BlockPos base(Companion c) {
		Colony colony = Colony.get();
		if (colony != null && colony.hasBase() && colony.world() == c.player().getServerWorld()) {
			return colony.base();
		}
		var home = c.memory().place("home");
		return home != null && home.dim.equals(c.dimension()) ? home.pos() : null;
	}
}
