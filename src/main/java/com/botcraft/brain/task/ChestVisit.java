package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.colony.Colony;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Walking up to a base chest and "opening" it like a player: get within reach with a clear
 * view, look at it, the lid opens, a short pause, then the items move. Shared by depositing and
 * withdrawing.
 */
final class ChestVisit {
	private static final int OPEN_TICKS = 8;
	private static final int GIVE_UP_TICKS = 20 * 45;

	@Nullable
	private BlockPos chest;
	private int ticks;
	private int openTicks;

	void start(BlockPos chest) {
		this.chest = chest.toImmutable();
		ticks = 0;
		openTicks = 0;
	}

	@Nullable
	BlockPos chest() {
		return chest;
	}

	/**
	 * @return the open container once we're at it and it's open, null while still getting there
	 * @throws IllegalStateException never; failure is reported through {@link #failed()}
	 */
	@Nullable
	Inventory tick(Companion c) {
		CompanionPlayer p = c.player();
		if (chest == null) {
			return null;
		}
		ticks++;
		Inventory inv = Colony.inventory(p.getServerWorld(), chest);
		if (inv == null) {
			failed = true;
			return null;
		}
		if (!c.actions().canReachBlock(chest)) {
			openTicks = 0;
			if (ticks == 1 || c.nav().state() == Navigator.State.IDLE) {
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), chest, 3.5), false);
			}
			if (c.nav().state() == Navigator.State.FAILED || ticks > GIVE_UP_TICKS) {
				failed = true;
			}
			return null;
		}
		c.nav().stop();
		c.actions().lookAt(Vec3d.ofCenter(chest));
		if (openTicks++ == 0) {
			inv.onOpen(p); // the lid opens (vanilla closes it again once nobody's looking)
			p.swingHand(Hand.MAIN_HAND);
			return null;
		}
		return openTicks >= OPEN_TICKS ? inv : null;
	}

	private boolean failed;

	boolean failed() {
		return failed;
	}

	void reset() {
		chest = null;
		failed = false;
		ticks = 0;
		openTicks = 0;
	}

	/** Put as much of {@code stack} as fits into the container. @return how many went in */
	static int insert(Inventory inv, ItemStack stack) {
		int start = stack.getCount();
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && ItemStack.canCombine(s, stack) && s.getCount() < s.getMaxCount()) {
				int n = Math.min(stack.getCount(), s.getMaxCount() - s.getCount());
				s.increment(n);
				stack.decrement(n);
			}
		}
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			if (inv.getStack(i).isEmpty() && inv.isValid(i, stack)) {
				inv.setStack(i, stack.split(Math.min(stack.getCount(), stack.getMaxCount())));
			}
		}
		inv.markDirty();
		return start - stack.getCount();
	}
}
