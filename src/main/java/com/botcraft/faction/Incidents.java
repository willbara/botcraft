package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.brain.Relationship;
import com.botcraft.colony.Colony;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What players (and other factions' companions) do to a faction, turned into standing and
 * relationships. The companion it happened to, and friends who saw it, feel it at once; the rest
 * of the faction hears about it when the victim calls it out in chat (that's the faction's
 * standing changing).
 */
public final class Incidents {
	/** Friends this close who can see it count as witnesses. */
	private static final double WITNESS_RANGE = 24;
	/** Opening a faction's chest counts against a player at most this often. */
	private static final long CHEST_COOLDOWN = 20 * 60;
	private static final Map<String, Long> LAST_CHEST = new HashMap<>();

	private Incidents() {
	}

	// ---- violence ---------------------------------------------------------------------------------

	/** A companion took damage (after armour, it really hurt). */
	public static void companionHurt(CompanionPlayer victimPlayer, DamageSource source, float amount) {
		CompanionManager m = CompanionManager.get();
		Companion victim = m == null ? null : m.byPlayer(victimPlayer);
		if (victim == null) {
			return;
		}
		Entity attacker = source.getAttacker();
		victim.onDamaged(amount, attacker);
		if (attacker instanceof CompanionPlayer otherPlayer) {
			Companion other = m.byPlayer(otherPlayer);
			if (other != null && other != victim) {
				victim.relations().note(other.name(), Relationship.Event.HIT);
				String a = Factions.idOf(victim);
				String b = Factions.idOf(other);
				if (!a.equals(b)) {
					Factions.clash(a, b, 3);
				}
			}
			return;
		}
		if (!(attacker instanceof ServerPlayerEntity player) || player == victimPlayer) {
			return;
		}
		// The owner's word stays law (absolute authority: still obeyed, never fought), but it's
		// still felt: the hit is remembered, and the faction's standing for them drops like anyone's.
		boolean owner = Hostility.isProtectedOwner(victim, player);
		String name = player.getGameProfile().getName();
		victim.relations().note(name, Relationship.Event.HIT);
		for (Companion w : witnesses(victim, player)) {
			w.relations().note(name, Relationship.Event.HIT);
			w.alarm();
		}
		Faction f = Factions.of(victim);
		Standing before = Factions.standing(f, player);
		Standing.Band was = before.band;
		Standing.Band changed = Factions.incident(f, player, Standing.Incident.HIT, 1);
		Standing.Band now = Factions.standing(f, player).band;
		Map<String, String> vars = Map.of("player", name);
		if (owner) {
			victim.speech().event(now == Standing.Band.HOSTILE ? "standing.owner_hostile" : "standing.owner_hit", vars, 20 * 20, 1.0F);
		} else if (changed != null && now == Standing.Band.HOSTILE) {
			victim.speech().event("standing.hostile", vars, 20 * 30, 1.0F);
		} else if (was == Standing.Band.FRIENDLY || was == Standing.Band.TRUSTED) {
			before.warnings++;
			victim.speech().event("standing.warn", vars, 20 * 20, 1.0F);
		} else {
			victim.speech().event("standing.report", vars, 20 * 20, 0.9F);
		}
	}

	/** A companion died; if a player (or another faction) killed it, that's remembered. */
	public static void companionKilled(Companion victim, @Nullable DamageSource source) {
		Entity attacker = source == null ? null : source.getAttacker();
		if (attacker instanceof CompanionPlayer otherPlayer) {
			CompanionManager m = CompanionManager.get();
			Companion other = m == null ? null : m.byPlayer(otherPlayer);
			if (other != null && other != victim) {
				victim.relations().note(other.name(), Relationship.Event.KILLED_ME);
				String a = Factions.idOf(victim);
				String b = Factions.idOf(other);
				if (!a.equals(b)) {
					Factions.clash(a, b, 10);
					Diplomacy.death(a, b, victim.now());
					Bounties.killed(Factions.of(victim), other.name(), isLeader(victim), victim.now());
				}
			}
			return;
		}
		if (!(attacker instanceof ServerPlayerEntity player)) {
			return;
		}
		// An owner with absolute authority is still obeyed and never fought, so no bounty; but a
		// killing is remembered and the standing falls (shown by /companion standing).
		boolean owner = Hostility.isProtectedOwner(victim, player);
		String name = player.getGameProfile().getName();
		victim.relations().note(name, Relationship.Event.KILLED_ME);
		for (Companion w : witnesses(victim, player)) {
			w.relations().note(name, Relationship.Event.HIT);
			w.speech().event(owner ? "standing.owner_murder" : "standing.murder", Map.of("player", name, "friend", victim.name()), 20 * 30, 1.0F);
		}
		Factions.incident(Factions.of(victim), player, Standing.Incident.KILLED, 1);
		if (!owner) {
			Bounties.killed(Factions.of(victim), name, isLeader(victim), victim.now());
		}
	}

	private static boolean isLeader(Companion c) {
		return Factions.of(c).rank(c.data.uuid.toString()) == Faction.Rank.LEADER;
	}

	/** A player killed a mob that was after one of us: that's a friend. */
	public static void mobKilled(ServerPlayerEntity killer, LivingEntity mob) {
		if (killer instanceof CompanionPlayer || !(mob instanceof MobEntity m) || !(m.getTarget() instanceof CompanionPlayer target)) {
			return;
		}
		CompanionManager mgr = CompanionManager.get();
		Companion helped = mgr == null ? null : mgr.byPlayer(target);
		if (helped == null || Hostility.isProtectedOwner(helped, killer)) {
			return;
		}
		helped.relations().note(killer.getGameProfile().getName(), Relationship.Event.HELPED);
		Factions.incident(Factions.of(helped), killer, Standing.Incident.HELPED, 1);
		helped.speech().event("standing.thanks_help", Map.of("player", killer.getGameProfile().getName()), 20 * 60, 0.8F);
	}

	/** Friends of the victim (same faction) close enough to see the attacker. */
	private static List<Companion> witnesses(Companion victim, Entity attacker) {
		List<Companion> out = new java.util.ArrayList<>();
		CompanionManager m = CompanionManager.get();
		if (m == null || victim.player() == null) {
			return out;
		}
		String faction = Factions.idOf(victim);
		for (Companion o : m.all()) {
			CompanionPlayer op = o.player();
			if (o == victim || op == null || !o.isOnline() || op.isDead() || op.getWorld() != victim.player().getWorld()
				|| !Factions.idOf(o).equals(faction) || op.distanceTo(attacker) > WITNESS_RANGE || !op.canSee(attacker)) {
				continue;
			}
			out.add(o);
		}
		return out;
	}

	// ---- property ---------------------------------------------------------------------------------

	/** A player opened a chest: if it's a faction's base chest and they're not one of us or trusted, that's theft. */
	public static void chestOpened(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
		if (player instanceof CompanionPlayer) {
			return;
		}
		for (Colony colony : Colony.all()) {
			if (colony.world() != world || !colony.isBaseChest(pos)) {
				continue;
			}
			Faction f = Factions.get(colony.id());
			if (f == null || Factions.isMember(f, player.getUuid())) {
				continue;
			}
			Standing s = Factions.standing(f, player);
			if (s.band == Standing.Band.TRUSTED) {
				continue;
			}
			long now = world.getTime();
			String key = f.id + "|" + player.getUuidAsString();
			if (now - LAST_CHEST.getOrDefault(key, -CHEST_COOLDOWN) < CHEST_COOLDOWN) {
				continue;
			}
			LAST_CHEST.put(key, now);
			Factions.incident(f, player, Standing.Incident.STOLE, 1);
			Companion seen = nearestMember(f.id, world, pos, 32);
			if (seen != null) {
				seen.relations().note(player.getGameProfile().getName(), Relationship.Event.STOLE);
				seen.speech().event("standing.chest", Map.of("player", player.getGameProfile().getName()), 20 * 30, 1.0F);
			}
		}
	}

	/** A player broke a block someone built at a faction's base. */
	public static void buildBroken(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
		if (player instanceof CompanionPlayer) {
			return;
		}
		for (Colony colony : Colony.all()) {
			if (colony.world() != world || !colony.hasBase() || !colony.near(world, pos, Colony.BASE_RADIUS)) {
				continue;
			}
			Faction f = Factions.get(colony.id());
			if (f == null || Factions.isMember(f, player.getUuid()) || Factions.standing(f, player).band == Standing.Band.TRUSTED) {
				continue;
			}
			Standing.Band changed = Factions.incident(f, player, Standing.Incident.BROKE_BUILD, 1);
			Companion seen = nearestMember(f.id, world, pos, 32);
			if (seen != null && changed != null) {
				seen.speech().event("standing.grief", Map.of("player", player.getGameProfile().getName()), 20 * 30, 1.0F);
			}
		}
	}

	/**
	 * A player closed a companion's inventory: items put in are a gift; items taken by someone
	 * who isn't trusted with it are theft. ({@code given} and {@code taken} are item counts.)
	 */
	public static void inventoryClosed(Companion c, ServerPlayerEntity viewer, int given, int taken) {
		if (viewer instanceof CompanionPlayer || Hostility.isProtectedOwner(c, viewer)) {
			return;
		}
		String name = viewer.getGameProfile().getName();
		Faction f = Factions.of(c);
		if (given > 0) {
			int stacks = Math.min(5, (given + 63) / 64);
			c.relations().note(name, Relationship.Event.GIFT);
			Factions.incident(f, viewer, Standing.Incident.GIFT, stacks);
			c.mood().bump(com.botcraft.brain.Mood.Kind.JOY, 0.1F, c.now());
			c.speech().event("standing.gift", Map.of("player", name), 20 * 20, 1.0F);
		}
		if (taken > 0 && Authority.of(c, viewer) != Authority.Level.ORDER) {
			int stacks = Math.min(5, (taken + 63) / 64);
			c.relations().note(name, Relationship.Event.STOLE);
			Factions.incident(f, viewer, Standing.Incident.STOLE, stacks);
			c.speech().event("standing.robbed", Map.of("player", name), 20 * 20, 1.0F);
		}
		AiLog.log(c.name(), "{} closed my inventory: gave {}, took {}", name, given, taken);
	}

	/** May this player open the companion's inventory? (Not if it's on bad terms with them.) */
	public static boolean mayOpenInventory(Companion c, ServerPlayerEntity viewer) {
		return Authority.of(c, viewer) != Authority.Level.NONE;
	}

	@Nullable
	private static Companion nearestMember(String faction, ServerWorld world, BlockPos pos, double range) {
		CompanionManager m = CompanionManager.get();
		if (m == null) {
			return null;
		}
		Companion best = null;
		double bestD = range * range;
		for (Companion o : m.all()) {
			CompanionPlayer op = o.player();
			if (op == null || !o.isOnline() || op.getWorld() != world || !Factions.idOf(o).equals(faction)) {
				continue;
			}
			double d = op.getBlockPos().getSquaredDistance(pos);
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}
}
