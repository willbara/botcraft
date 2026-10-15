package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Drives;
import com.botcraft.colony.Colony;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Factions at war raiding each other. Every minute, each faction whose warfare is "raid"
 * (dynamic factions always) looks at the enemy bases it knows: if it has enough armed, healthy
 * members living on their own and they clearly outmatch the defenders it can count, its leader
 * (the most aggressive one) may send a party. Raiders go, fight, take what they can (griefing
 * on) and come home to put it away. A faction raids at most every 15 minutes.
 */
public final class Raids {
	private static final long CHECK_TICKS = 20 * 60;
	private static final long COOLDOWN = 20 * 60 * 15;
	private static final int MAX_PARTY = 4;
	private static final Map<String, Long> NEXT = new HashMap<>();
	private static final Random RANDOM = new Random();

	private Raids() {
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % CHECK_TICKS != 37 || !Factions.enabled()) {
			return;
		}
		for (Faction f : Factions.groups()) {
			if (!"raid".equals(Factions.warfare(f)) || now < NEXT.getOrDefault(f.id, 0L)) {
				continue;
			}
			try {
				consider(f, now, false);
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error planning a raid for {}", f.id, e);
			}
		}
	}

	/**
	 * Send a raid now if it makes sense ({@code force}: as long as there's anyone to send).
	 * @return who was sent, or why not
	 */
	public static String consider(Faction f, long now, boolean force) {
		Colony home = Colony.of(f.id);
		if (home == null || !home.hasBase()) {
			return "no base to raid from";
		}
		Faction target = target(f, home, force);
		if (target == null) {
			return "no enemy base known";
		}
		List<Companion> party = party(f.id, force);
		if (party.isEmpty() || (!force && party.size() < 2)) {
			NEXT.put(f.id, now + COOLDOWN / 3);
			return "not enough fighters free";
		}
		double ours = 0;
		for (Companion c : party) {
			ours += strength(c.player());
		}
		double theirs = 0;
		Colony them = Colony.of(target.id);
		for (Companion c : Factions.members(target.id)) {
			CompanionPlayer p = c.player();
			if (p != null && c.isOnline() && !p.isDead() && them != null && them.near(p.getServerWorld(), p.getBlockPos(), 64)) {
				theirs += strength(p);
			}
		}
		float aggression = 0;
		for (Companion c : party) {
			aggression = Math.max(aggression, c.brain().personality().aggression());
		}
		// Clear superiority needed; aggressive leaders settle for less.
		double needed = 1.4 - 0.5 * aggression;
		if (!force && (theirs * needed > ours || RANDOM.nextFloat() > 0.35F + 0.6F * aggression)) {
			NEXT.put(f.id, now + COOLDOWN / 3);
			return String.format(java.util.Locale.ROOT, "decided against it (strength %.1f vs %.1f)", ours, theirs);
		}
		NEXT.put(f.id, now + COOLDOWN);
		List<String> names = new ArrayList<>();
		for (Companion c : party) {
			String prev = Scope.enter(f.id);
			try {
				c.life().abandon(now);
				c.jobs().replace(List.of("raid " + target.id, "home", "deposit"));
				c.brain().reconsider();
				c.speech().event("raid.go", Map.of("faction", target.name), 20 * 60, 1.0F);
			} finally {
				Scope.exit(prev);
			}
			names.add(c.name());
		}
		AiLog.log("faction", "{} raids {} with {} (strength {} vs {})", f.id, target.id, names, ours, theirs);
		return "sent " + String.join(", ", names) + " to raid " + target.name;
	}

	/** The nearest enemy faction with a base in the same world, whose land borders ours (any, when the raid is ordered). */
	@Nullable
	private static Faction target(Faction f, Colony home, boolean force) {
		Faction best = null;
		double bestD = Double.MAX_VALUE;
		for (Faction o : Factions.groups()) {
			if (o == f || !Factions.hostile(f.id, o.id) || !force && !Territory.neighbours(f.id, o.id)) {
				continue;
			}
			Colony them = Colony.of(o.id);
			if (them == null || !them.hasBase() || them.world() != home.world()) {
				continue;
			}
			// The nearest, but a faction harbouring the wanted is worth going further for.
			double d = them.base().getSquaredDistance(home.base()) / (1.0 + 0.25 * Bounties.against(f, o.id, home.world().getTime()));
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	/** Armed, healthy members living on their own and not busy with orders, strongest first. */
	private static List<Companion> party(String faction, boolean force) {
		List<Companion> out = new ArrayList<>();
		for (Companion c : Factions.members(faction)) {
			CompanionPlayer p = c.player();
			if (p == null || !c.isOnline() || p.isDead() || (!force && !"free".equals(c.data.mode))) {
				continue;
			}
			boolean armed = Inventories.bestWeaponDamage(p.getInventory()) >= Drives.ARMED_DAMAGE;
			if (!armed || p.getHealth() < p.getMaxHealth() * 0.7F || c.brain().job() instanceof com.botcraft.brain.task.RaidTask) {
				continue;
			}
			out.add(c);
		}
		out.sort(Comparator.comparingDouble((Companion c) -> -strength(c.player())));
		return out.size() > MAX_PARTY ? new ArrayList<>(out.subList(0, MAX_PARTY)) : out;
	}

	/** Rough fighting strength: weapon damage, armour and health. */
	static double strength(@Nullable CompanionPlayer p) {
		if (p == null) {
			return 0;
		}
		return Inventories.bestWeaponDamage(p.getInventory()) * (1.0 + p.getArmor() / 20.0) * (p.getHealth() / 20.0);
	}

	/** For server stop. */
	public static void clear() {
		NEXT.clear();
	}
}
