package com.mcfriends.brain;

import com.mcfriends.Companion;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Where everyone is working, so companions spread out instead of crowding the same tree,
 * ore vein, cave or herd. A task claims its current target; when choosing among candidates,
 * spots near someone else's claim (or near another player/companion standing there) are
 * avoided unless nothing else is available.
 */
public final class Claims {
	private static final Map<String, BlockPos> CLAIMS = new ConcurrentHashMap<>();
	/** Claims older than this are ignored (a task that ended without releasing). */
	private static final long STALE_TICKS = 20 * 60 * 3;
	private static final Map<String, Long> CLAIMED_AT = new ConcurrentHashMap<>();

	private Claims() {
	}

	public static void claim(Companion c, @Nullable BlockPos pos) {
		if (pos == null) {
			release(c);
			return;
		}
		CLAIMS.put(c.name(), pos.toImmutable());
		CLAIMED_AT.put(c.name(), c.now());
	}

	public static void release(Companion c) {
		CLAIMS.remove(c.name());
		CLAIMED_AT.remove(c.name());
	}

	/** Someone else is working at (or standing) within {@code radius} of this spot. */
	public static boolean crowded(Companion self, BlockPos pos, double radius) {
		double r2 = radius * radius;
		for (Map.Entry<String, BlockPos> e : CLAIMS.entrySet()) {
			if (e.getKey().equals(self.name())) {
				continue;
			}
			long at = CLAIMED_AT.getOrDefault(e.getKey(), 0L);
			if (self.now() - at > STALE_TICKS) {
				continue;
			}
			if (e.getValue().getSquaredDistance(pos) <= r2) {
				return true;
			}
		}
		for (ServerPlayerEntity p : self.server.getPlayerManager().getPlayerList()) {
			if (p != self.player() && p.getWorld() == self.player().getWorld() && p.getBlockPos().getSquaredDistance(pos) <= r2) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Keep the candidates nobody else is working near; if that leaves nothing, keep them all
	 * (better to share than to give up).
	 */
	public static <T> List<T> spreadOut(Companion self, List<T> candidates, Function<T, BlockPos> where, double radius) {
		List<T> free = new ArrayList<>();
		for (T t : candidates) {
			if (!crowded(self, where.apply(t), radius)) {
				free.add(t);
			}
		}
		return free.isEmpty() ? candidates : free;
	}

	/** For tests: forget everything. */
	public static void clear() {
		CLAIMS.clear();
		CLAIMED_AT.clear();
	}
}
