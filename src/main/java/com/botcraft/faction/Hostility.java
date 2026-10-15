package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.colony.Colony;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Who a companion treats as an enemy among players (real ones and other factions' companions),
 * by its faction's warfare setting:
 * <ul>
 *   <li><b>off:</b> nobody;</li>
 *   <li><b>defend:</b> whoever just hit it, and enemies (hostile standing, or a hostile faction)
 *       who are inside its base or just attacked one of its own;</li>
 *   <li><b>pvp</b> and <b>raid:</b> enemies on sight, and whoever just hit it.</li>
 * </ul>
 * A companion's owner is never an enemy while owners have absolute authority.
 */
public final class Hostility {
	/** Hit us this recently: hit back, whatever the standing. */
	public static final long GRUDGE_TICKS = 20 * 20;
	/** Attacked one of us this recently: defenders join in. */
	private static final long INCIDENT_TICKS = 20 * 30;
	/** Inside this distance of the base counts as being in our territory. */
	public static final double TERRITORY = 48;
	/** Back from the dead this recently: only fight back, don't go looking for it. */
	public static final long RESPAWN_GRACE = 20 * 60;

	private Hostility() {
	}

	/** Is {@code other} someone {@code c} should fight? */
	public static boolean isEnemy(Companion c, ServerPlayerEntity other) {
		var self = c.player();
		if (self == null || other == self || !other.isAlive() || other.isSpectator() || other.isCreative()) {
			return false;
		}
		Faction mine = Factions.of(c);
		String warfare = Factions.warfare(mine);
		if ("off".equals(warfare)) {
			return false;
		}
		boolean onSight = "pvp".equals(warfare) || "raid".equals(warfare);
		// Just respawned (probably without its gear): only hits back, so a death isn't followed by
		// walking straight back into the same fight.
		boolean fresh = c.now() - c.spawnedAt < RESPAWN_GRACE;
		if (other instanceof CompanionPlayer) {
			CompanionManager m = CompanionManager.get();
			Companion o = m == null ? null : m.byPlayer(other);
			if (o == null || !Factions.hostile(mine.id, Factions.idOf(o))) {
				return false;
			}
			boolean grudge = c.relations().grudge(o.name(), GRUDGE_TICKS);
			if (fresh) {
				return grudge;
			}
			return onSight || grudge || Bounties.wanted(mine, o.name(), c.now()) || inTerritory(mine, other);
		}
		if (isProtectedOwner(c, other)) {
			return false;
		}
		String name = other.getGameProfile().getName();
		if (c.relations().grudge(name, GRUDGE_TICKS)) {
			return true; // self-defence
		}
		if (fresh) {
			return false;
		}
		boolean enemy = isHostilePlayer(mine, other);
		if (!enemy) {
			return false;
		}
		if (onSight || Bounties.wanted(mine, name, c.now())) {
			return true;
		}
		Standing s = Factions.peek(mine, other.getUuid());
		long now = other.getServer().getOverworld().getTime();
		return inTerritory(mine, other) || (s != null && now - s.lastIncident < INCIDENT_TICKS);
	}

	/** The faction thinks of this player as an enemy: hostile standing, or a member of a faction it's hostile to. */
	public static boolean isHostilePlayer(Faction mine, ServerPlayerEntity other) {
		Standing s = Factions.peek(mine, other.getUuid());
		if (s != null && s.band == Standing.Band.HOSTILE) {
			return true;
		}
		for (Faction f : Factions.all()) {
			if (f != mine && f.rank(other.getUuidAsString()) != null && Factions.hostile(mine.id, f.id)) {
				return true;
			}
		}
		return false;
	}

	/** The owner of this companion, while owners have absolute authority. */
	public static boolean isProtectedOwner(Companion c, @Nullable ServerPlayerEntity player) {
		return player != null && player.getUuid().equals(c.data.owner) && !"standing".equals(CompanionConfig.get().ownerAuthority);
	}

	/** On the faction's land ({@link Territory}), or close to its base. */
	public static boolean inTerritory(Faction f, ServerPlayerEntity other) {
		Colony colony = Colony.of(f.id);
		return colony != null && colony.hasBase() && colony.world() == other.getServerWorld()
			&& (colony.near(other.getServerWorld(), other.getBlockPos(), TERRITORY) || Territory.inside(f.id, other.getServerWorld(), other.getBlockPos()));
	}
}
