package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Builds;
import com.botcraft.colony.Colony;
import com.botcraft.colony.Upkeep;
import com.botcraft.colony.Upkeep.Kind;
import com.botcraft.colony.Upkeep.Work;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.BreakPolicy;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tidy up the base ({@link Upkeep}): take down litter, dig away bumps in the zones and fill
 * holes, nearest job first, until there's nothing left or we've been at it a while. Holes are
 * filled with dirt (or other throwaway blocks) we carry; the blocks that go in become ground,
 * not litter. What gets mined is picked up afterwards like any other drop.
 */
public class TidyTask extends Task {
	/** Jobs per request: the rest goes back on the board for whoever's free next. */
	public static final int MAX_JOBS = 16;
	private static final int JOB_TICKS = 20 * 25;
	private static final int NEAR = 8;

	private final Set<Work> skipped = new HashSet<>();
	@Nullable
	private Work current;
	private int jobTicks;
	private int done;
	private boolean noBlocks;

	public TidyTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "tidy";
	}

	@Override
	public String describe() {
		return "tidying up the base (" + done + " done)";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 300;
	}

	@Override
	protected void onStop() {
		c.nav().stop();
	}

	@Override
	protected Status onTick() {
		Colony colony = Colony.get();
		CompanionPlayer p = c.player();
		if (colony == null || !colony.hasBase() || colony.world() != p.getServerWorld()) {
			return fail("there's no base here to tidy");
		}
		if (current == null || !needed(colony, p.getServerWorld(), current)) {
			current = next(colony, p);
			jobTicks = 0;
			if (c.nav().state() == Navigator.State.FAILED) {
				c.nav().stop(); // forget the failure getting to the last one
			}
			if (current == null || done >= MAX_JOBS) {
				return finish();
			}
		}
		// The clock for a job starts once we're near it (coming back from a cave can take a while).
		if (current.pos().getSquaredDistance(p.getBlockPos()) < NEAR * NEAR && ++jobTicks > JOB_TICKS) {
			skip("took too long");
			return Status.RUNNING;
		}
		return current.kind() == Kind.FILL ? fill(colony, p) : dig(p);
	}

	@Nullable
	private Work next(Colony colony, CompanionPlayer p) {
		Work k = pick(colony, p, Upkeep.current(colony, c.now(), 40));
		// Nothing left in what was found a moment ago: look again before calling it done.
		return k != null ? k : pick(colony, p, Upkeep.current(colony, c.now(), 0));
	}

	/**
	 * The nearest job we can do now: in a column, blocks come off from the top down and holes
	 * fill from the bottom up, so a job waits while the one above (or below) it is still to do.
	 */
	@Nullable
	private Work pick(Colony colony, CompanionPlayer p, List<Work> work) {
		ServerWorld w = p.getServerWorld();
		List<Work> todo = work.stream().filter(k -> !skipped.contains(k) && !(k.kind() == Kind.FILL && noBlocks) && needed(colony, w, k)).toList();
		Set<Long> breaking = new HashSet<>();
		Set<Long> filling = new HashSet<>();
		for (Work k : todo) {
			(k.kind() == Kind.FILL ? filling : breaking).add(k.pos().asLong());
		}
		return todo.stream().filter(k -> k.kind() == Kind.FILL ? !filling.contains(k.pos().down().asLong()) : !breaking.contains(k.pos().up().asLong()))
			.min(Comparator.comparingDouble(k -> k.pos().getSquaredDistance(p.getBlockPos()))).orElse(null);
	}

	/** Still to do, and still safe to do (things change while we walk over). */
	private static boolean needed(Colony colony, ServerWorld w, Work k) {
		BlockState s = w.getBlockState(k.pos());
		return switch (k.kind()) {
			case LITTER -> BreakPolicy.placedByCompanion(w, k.pos(), s);
			case DIG -> BreakPolicy.isNaturalTerrain(s) && Builds.isNatural(w, k.pos(), s) && !colony.isBaseChest(k.pos().up());
			case FILL -> s.isReplaceable() && s.getFluidState().isEmpty();
		};
	}

	private Status dig(CompanionPlayer p) {
		if (!c.actions().canReachBlock(current.pos())) {
			walkTo(p);
			return Status.RUNNING;
		}
		c.nav().stop();
		ActionController.MineResult r = c.actions().mineBlock(current.pos());
		if (r == ActionController.MineResult.DONE) {
			done++;
			current = null;
		} else if (r == ActionController.MineResult.FAILED) {
			skip("couldn't mine it");
		}
		return Status.RUNNING;
	}

	private Status fill(Colony colony, CompanionPlayer p) {
		Predicate<ItemStack> block = Inventories.count(p.getInventory(), s -> s.isOf(Items.DIRT)) > 0 ? s -> s.isOf(Items.DIRT)
			: Inventories::isThrowaway;
		if (Inventories.count(p.getInventory(), block) == 0) {
			noBlocks = true; // nothing to fill with: the rest of the holes wait for someone who has
			current = null;
			return Status.RUNNING;
		}
		boolean inTheWay = p.getBoundingBox().intersects(new Box(current.pos()));
		if (inTheWay || !c.actions().canReachBlock(current.pos())) {
			walkTo(p);
			return Status.RUNNING;
		}
		c.nav().stop();
		if (c.actions().placeBlock(current.pos(), block)) {
			String dim = p.getServerWorld().getRegistryKey().getValue().toString();
			colony.forgetPlaced(dim, current.pos()); // ground now, not litter
			colony.forgetDug(dim, current.pos());
			done++;
			current = null;
		} else if (jobTicks > 60) {
			skip("couldn't place a block there (" + c.actions().lastPlaceProblem() + ")");
		}
		return Status.RUNNING;
	}

	private void walkTo(CompanionPlayer p) {
		if (c.nav().state() == Navigator.State.FAILED) {
			skip("can't get there");
			return;
		}
		c.nav().moveTo(Goal.reachVisible(p.getWorld(), current.pos(), 4.0), false);
	}

	private void skip(String why) {
		AiLog.log(c.name(), "tidy: skipping {} at {}: {}", current.kind(), current.pos().toShortString(), why);
		skipped.add(current);
		current = null;
		c.nav().stop();
	}

	private Status finish() {
		c.nav().stop();
		AiLog.log(c.name(), "tidy: done {} job(s){}", done, noBlocks ? " (out of blocks to fill holes with)" : "");
		return done > 0 ? Status.SUCCESS : fail(noBlocks ? "I've nothing to fill the holes with" : "I couldn't get any of it done");
	}
}
