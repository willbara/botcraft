package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.chat.Intent;
import com.botcraft.config.CompanionConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Who may tell a companion what to do.
 * <ul>
 *   <li>The player who spawned it (its owner) always, while {@code ownerAuthority} is "absolute";
 *       with "standing" the owner is judged like everyone else (starting well liked).</li>
 *   <li>Leaders and officers of its faction may give it orders.</li>
 *   <li>Anyone else by their standing with its faction: trusted players may give orders,
 *       friendly ones may ask for things (hand me that, come here), neutral ones may only ask
 *       questions (and don't get told where the base is or what's in the chests), and enemies
 *       get nothing.</li>
 * </ul>
 */
public final class Authority {
	public enum Level {
		/** An enemy: no orders, no answers. */
		NONE,
		/** Questions and small talk, nothing private. */
		ASK,
		/** Small favours, and private questions. */
		REQUEST,
		/** Anything. */
		ORDER
	}

	/** What a friendly player may ask for. */
	private static final Set<Intent.Type> REQUESTS = Set.of(Intent.Type.GIVE, Intent.Type.COME_HERE, Intent.Type.BRING, Intent.Type.LIGHT,
		Intent.Type.DANCE, Intent.Type.HUNT, Intent.Type.SMELT, Intent.Type.CRAFT, Intent.Type.CANCEL, Intent.Type.FARM, Intent.Type.FISH);
	/** Questions only a friendly player gets an answer to (where we are, what we and the base have). */
	private static final Set<Intent.Type> PRIVATE = Set.of(Intent.Type.WHERE, Intent.Type.STOCK, Intent.Type.FOUND, Intent.Type.INVENTORY,
		Intent.Type.STATS, Intent.Type.COLONY_WANT);

	private Authority() {
	}

	/** How far {@code sender} may go with {@code c} (null sender = the console: anything). */
	public static Level of(Companion c, @Nullable ServerPlayerEntity sender) {
		if (sender == null || c.data.owner == null) {
			return Level.ORDER; // the console, or a companion nobody owns yet (the first to give an order adopts it)
		}
		boolean owner = sender.getUuid().equals(c.data.owner);
		if (owner && !"standing".equals(CompanionConfig.get().ownerAuthority)) {
			return Level.ORDER;
		}
		Faction f = Factions.of(c);
		Faction.Rank rank = f.rank(sender.getUuidAsString());
		if (rank == Faction.Rank.LEADER || rank == Faction.Rank.OFFICER) {
			return Level.ORDER;
		}
		Standing s = Factions.standing(f, sender);
		if (c.relations().grudge(sender.getGameProfile().getName(), Hostility.GRUDGE_TICKS)) {
			return Level.NONE; // just hit us
		}
		return switch (s.band) {
			case TRUSTED -> Level.ORDER;
			case FRIENDLY -> Level.REQUEST;
			case NEUTRAL -> Level.ASK;
			case HOSTILE -> Level.NONE;
		};
	}

	/** May someone with this level have this done? */
	public static boolean allows(Level level, Intent.Type type) {
		return switch (level) {
			case ORDER -> true;
			case REQUEST -> !type.isOrder() || REQUESTS.contains(type);
			case ASK -> !type.isOrder() && !PRIVATE.contains(type);
			case NONE -> false;
		};
	}
}
