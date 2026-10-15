package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.entity.SkinResolver;
import com.botcraft.util.AiLog;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Companions with special names (turned off with {@code easterEggs: false}):
 * <ul>
 *   <li><b>popbob</b>: a factionless menace that gears up first, then raids faction bases,
 *       steals from their chests, wrecks what they built and bothers people ({@link Popbob}).</li>
 *   <li><b>Herobrine</b>: silent, unarmoured, factionless. Watches from the edge of sight at
 *       night and vanishes when approached; leaves empty signs about; strips leaves off trees
 *       ({@link Herobrine}).</li>
 *   <li><b>Technoblade</b>: an ordinary companion, but fearless (never flees), keen on hunting
 *       pigs and enemy companions, and never dies (so he says, every time he's back).</li>
 *   <li><b>Hausemaster</b>: plays for itself (free mode, no faction, no orders), silent, can't be
 *       killed, and teleports away from popbob.</li>
 * </ul>
 * Each wears its namesake's skin: the account's skin is fetched when it joins, if it isn't on.
 */
public final class EasterEggs {
	public enum Kind {
		POPBOB(null, false, true),
		HEROBRINE("MHF_Herobrine", true, true),
		TECHNOBLADE("Technoblade", false, false),
		HAUSEMASTER("Hausemaster", true, true);

		/** The account whose skin it wears (null: whatever it was given). */
		@Nullable
		final String skin;
		final boolean silent;
		final boolean factionless;

		Kind(@Nullable String skin, boolean silent, boolean factionless) {
			this.skin = skin;
			this.silent = silent;
			this.factionless = factionless;
		}
	}

	private static final Random RANDOM = new Random();
	/** Skin fetches already tried this session (so a failed lookup isn't retried every respawn). */
	private static final Set<String> SKIN_TRIED = new HashSet<>();

	private EasterEggs() {
	}

	@Nullable
	public static Kind of(Companion c) {
		return of(c.name());
	}

	@Nullable
	public static Kind of(String name) {
		if (!CompanionConfig.get().easterEggs) {
			return null;
		}
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "popbob" -> Kind.POPBOB;
			case "herobrine" -> Kind.HEROBRINE;
			case "technoblade" -> Kind.TECHNOBLADE;
			case "hausemaster" -> Kind.HAUSEMASTER;
			default -> null;
		};
	}

	public static boolean is(Companion c, Kind k) {
		return of(c) == k;
	}

	/** Never says anything (and ignores chat). */
	public static boolean silent(Companion c) {
		Kind k = of(c);
		return k != null && k.silent;
	}

	/** Belongs to no faction and can't be made to join one. */
	public static boolean factionless(Companion c) {
		Kind k = of(c);
		return k != null && k.factionless;
	}

	/** Takes no orders from anyone. */
	public static boolean ignoresOrders(Companion c) {
		Kind k = of(c);
		return k == Kind.POPBOB || k == Kind.HEROBRINE || k == Kind.HAUSEMASTER;
	}

	/** Fearless: never runs or retreats. */
	public static boolean fearless(Companion c) {
		return of(c) == Kind.TECHNOBLADE;
	}

	/** Can't be hurt. */
	public static boolean unkillable(Companion c) {
		return of(c) == Kind.HAUSEMASTER;
	}

	/** Takes no armour (Herobrine wears none). */
	public static boolean noArmour(Companion c) {
		return of(c) == Kind.HEROBRINE;
	}

	/** Joined, rejoined or came back from death: dress it, set its character, say its line. */
	public static void onSpawned(Companion c, String reason) {
		Kind k = of(c);
		if (k == null) {
			return;
		}
		c.data.faction = k.factionless ? null : c.data.faction;
		if (k == Kind.POPBOB || k == Kind.HAUSEMASTER || k == Kind.HEROBRINE) {
			c.data.mode = "free"; // nobody's follower
		}
		if (k == Kind.TECHNOBLADE) {
			c.data.bravery = 1.0F;
			Personality.set(c.data, "aggression", 1.0F);
			Personality.set(c.data, "caution", 0.05F);
			Personality.set(c.data, "ambition", Math.max(0.8F, Personality.trait(c.data, "ambition")));
			if (!"summoned".equals(reason)) {
				c.speech().event("egg.technoblade", Map.of(), 0, 1.0F); // every time he's back
			}
		}
		if (k == Kind.HEROBRINE) {
			stripArmour(c);
		}
		ensureSkin(c, k);
	}

	/** Its namesake's skin, fetched from the account if it isn't wearing it (once a session). */
	private static void ensureSkin(Companion c, Kind k) {
		if (k.skin == null || k.skin.equalsIgnoreCase(c.data.skin) && c.data.skinValue != null) {
			return;
		}
		if (!CompanionConfig.get().allowSkinLookup || !SKIN_TRIED.add(c.name().toLowerCase(Locale.ROOT))) {
			return;
		}
		lookupSkin(c, k.skin, k == Kind.HEROBRINE ? "Herobrine" : null);
	}

	private static void lookupSkin(Companion c, String account, @Nullable String fallback) {
		SkinResolver.resolve(c.server, account, c.name(), result -> {
			CompanionManager m = CompanionManager.get();
			if (m == null || m.byName(c.name()) != c) {
				return;
			}
			if (result.choice().hasTextures()) {
				AiLog.log(c.name(), "egg: wearing {}'s skin", account);
				m.changeSkin(c, result.choice());
			} else if (fallback != null) {
				lookupSkin(c, fallback, null);
			}
		});
	}

	/** Armour off and into the pockets. */
	public static void stripArmour(Companion c) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return;
		}
		var inv = p.getInventory();
		for (int i = 0; i < inv.armor.size(); i++) {
			var s = inv.armor.get(i);
			if (!s.isEmpty()) {
				var copy = s.copy();
				inv.armor.set(i, net.minecraft.item.ItemStack.EMPTY);
				if (!inv.insertStack(copy)) {
					p.dropItem(copy, false);
				}
			}
		}
	}

	/**
	 * Vanish: off to a spot {@code min}-{@code max} blocks away (away from {@code from} if given),
	 * on the surface, in loaded ground. True if it went.
	 */
	public static boolean vanish(Companion c, @Nullable Vec3d from, int min, int max) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return false;
		}
		ServerWorld w = p.getServerWorld();
		for (int attempt = 0; attempt < 12; attempt++) {
			double angle;
			if (from != null) {
				angle = Math.atan2(p.getZ() - from.z, p.getX() - from.x) + (RANDOM.nextDouble() - 0.5) * Math.PI * 0.9;
			} else {
				angle = RANDOM.nextDouble() * Math.PI * 2;
			}
			int dist = min + RANDOM.nextInt(Math.max(1, max - min));
			int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist);
			int z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
			if (!w.isChunkLoaded(x >> 4, z >> 4) || !w.getWorldBorder().contains(new BlockPos(x, 0, z))) {
				continue;
			}
			int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos feet = new BlockPos(x, y, z);
			if (!w.getFluidState(feet.down()).isEmpty() || !w.getBlockState(feet).isAir() || !w.getBlockState(feet.up()).isAir()) {
				continue;
			}
			c.nav().stop();
			p.teleport(w, x + 0.5, y, z + 0.5, p.getYaw(), p.getPitch());
			AiLog.log(c.name(), "egg: vanished to {}", feet.toShortString());
			return true;
		}
		return false;
	}

	/** The nearest living player or companion other than this one (and other than the names given), within range. */
	@Nullable
	public static net.minecraft.server.network.ServerPlayerEntity nearestOther(Companion c, double range, Kind... ignore) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return null;
		}
		net.minecraft.server.network.ServerPlayerEntity best = null;
		double bestD = range * range;
		List<Kind> skip = List.of(ignore);
		CompanionManager m = CompanionManager.get();
		for (var o : p.getServerWorld().getPlayers()) {
			if (o == p || !o.isAlive() || o.isSpectator()) {
				continue;
			}
			Companion oc = m == null ? null : m.byPlayer(o);
			if (oc != null && skip.contains(of(oc))) {
				continue;
			}
			double d = o.squaredDistanceTo(p);
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	public static void clear() {
		SKIN_TRIED.clear();
	}
}
