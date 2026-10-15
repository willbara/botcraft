package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.brain.Personality;
import com.botcraft.colony.Colony;
import com.botcraft.util.AiLog;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A leader's plans for the faction (teams and dynamic mode), every few minutes by day, on top of
 * handing out the board's work (see Leadership). The leader (or an officer while it's away) weighs
 * what the faction has and wants, and its own character, and may:
 * <ul>
 *   <li><b>Expand:</b> send members out to stake claims on free land at the frontier (a chunk on
 *       the border with one of ours standing in it becomes ours; see Territory). When it's under
 *       its land cap and there's free land: ambitious leaders do it more.</li>
 *   <li><b>Scout:</b> send its most curious idle member to look over the land past the border
 *       (towards a faction it hasn't met, else somewhere new), and come back. Scouts notice water,
 *       herds and caves on the way like anyone.</li>
 *   <li><b>Go to war for resources</b> (dynamic mode): a struggling faction (its base under-stocked)
 *       next to a neighbour that's richer and weaker may declare war on it and raid it straight
 *       away. Aggressive, ambitious leaders do; peaceful ones don't. Only between neighbours, never
 *       in grace or a truce.</li>
 * </ul>
 */
public final class Strategy {
	/** How often a leader thinks about the bigger picture. */
	static final long PLAN_TICKS = 20 * 60 * 3;
	/** Most members sent off staking claims at once. */
	private static final int MAX_STAKERS = 3;
	private static final Map<String, Long> NEXT = new HashMap<>();
	private static final Random RANDOM = new Random();

	private Strategy() {
	}

	/** From Leadership's round (in the faction's scope). */
	static void plan(Faction f, long now) {
		if (now < NEXT.getOrDefault(f.id, 0L)) {
			return;
		}
		NEXT.put(f.id, now + PLAN_TICKS + RANDOM.nextInt(20 * 30));
		Colony colony = Colony.get();
		if (colony == null || !colony.hasBase() || colony.world() == null) {
			return;
		}
		List<Companion> members = Factions.members(f.id);
		Companion boss = Leadership.commander(f, members);
		if (boss == null || boss.player() == null || boss.perception().isNight() || members.size() < 2) {
			return;
		}
		Personality p = boss.brain().personality();
		double perf = Leadership.performance(colony);
		// War for what they've got: needs a hungry faction and a hard leader.
		if (Factions.dynamic() && perf < 0.5 && p.aggression() + p.ambition() > 1.1) {
			Faction victim = richAndWeak(f, colony, members, now);
			if (victim != null && RANDOM.nextDouble() < 0.3 + 0.4 * (p.aggression() - 0.5)) {
				Diplomacy.declare(f.id, victim.id, "faction.war_resources", now);
				String raid = Raids.consider(f, now, true);
				AiLog.log("faction", "{}: {} goes to war with {} for their stores ({})", f.id, boss.name(), victim.id, raid);
				return;
			}
		}
		List<Companion> idle = new ArrayList<>();
		for (Companion c : members) {
			if (Leadership.idle(c) && c != boss) {
				idle.add(c);
			}
		}
		if (idle.isEmpty()) {
			return;
		}
		// Expand: more land means more room, more trees and animals, and a bigger say at the border.
		if (Territory.on() && Territory.roomToGrow(f, colony) && RANDOM.nextDouble() < 0.4 + 0.5 * p.ambition()) {
			idle.sort(Comparator.comparingDouble((Companion c) -> -(c.brain().personality().bravery() + c.brain().personality().ambition())));
			List<BlockPos> spots = Territory.freeFrontier(f, colony, Math.min(MAX_STAKERS, idle.size()));
			for (int i = 0; i < spots.size(); i++) {
				Companion c = idle.get(i);
				BlockPos s = surface(colony, spots.get(i));
				c.jobs().append(List.of("goto " + s.getX() + " " + s.getY() + " " + s.getZ() + " to stake our claim", "home"));
				if (i == 0) {
					boss.speech().event("faction.expand", Map.of("name", c.name()), 20 * 60, 0.9F);
					c.speech().sayIn("faction.order_ack", null, Map.of("leader", boss.name(), "task", "staking a claim"), 50);
				}
			}
			AiLog.log("faction", "{}: {} sends {} out to stake claims", f.id, boss.name(), spots.size());
			idle.subList(0, spots.size()).clear();
			if (idle.isEmpty()) {
				return;
			}
		}
		// Scout: the most curious one left looks over what's past the border.
		if (RANDOM.nextDouble() < 0.25 + 0.4 * p.curiosity()) {
			idle.sort(Comparator.comparingDouble((Companion c) -> -c.brain().personality().curiosity()));
			Companion scout = idle.get(0);
			BlockPos to = scoutTarget(f, colony);
			scout.jobs().append(List.of("goto " + to.getX() + " " + to.getY() + " " + to.getZ() + " scouting", "home"));
			boss.speech().event("faction.scout", Map.of("name", scout.name()), 20 * 60, 0.9F);
			scout.speech().sayIn("faction.order_ack", null, Map.of("leader", boss.name(), "task", "scouting"), 50);
			AiLog.log("faction", "{}: {} sends {} scouting towards {}", f.id, boss.name(), scout.name(), to.toShortString());
		}
	}

	/**
	 * A neighbour worth robbing: more in its chests than we have (by value), and fewer fighters at
	 * home than we can send. The richest such, or null.
	 */
	@Nullable
	private static Faction richAndWeak(Faction f, Colony colony, List<Companion> members, long now) {
		double ourWealth = wealth(colony);
		double ourStrength = 0;
		for (Companion c : members) {
			ourStrength += Raids.strength(c.player());
		}
		Faction best = null;
		double bestWealth = 0;
		for (Faction o : Factions.groups()) {
			if (o == f || !Diplomacy.mayDeclare(f.id, o.id, now)) {
				continue;
			}
			Colony them = Colony.of(o.id);
			if (them == null || !them.hasBase()) {
				continue;
			}
			double theirWealth = wealth(them);
			double theirStrength = 0;
			for (Companion c : Factions.members(o.id)) {
				theirStrength += Raids.strength(c.player());
			}
			if (theirWealth > ourWealth * 1.5 + 20 && ourStrength > theirStrength * 1.1 && theirWealth > bestWealth) {
				best = o;
				bestWealth = theirWealth;
			}
		}
		return best;
	}

	/** What a base's chests are worth to a raider (the raid's value tiers, by count). */
	static double wealth(Colony colony) {
		double sum = 0;
		for (var e : colony.stock().entrySet()) {
			int tier = com.botcraft.brain.task.RaidTask.worth(new ItemStack(e.getKey()));
			sum += tier * tier * e.getValue();
		}
		return sum;
	}

	/** Past the border: towards a faction we haven't met, else a random direction, 120-200 blocks out. */
	private static BlockPos scoutTarget(Faction f, Colony colony) {
		BlockPos base = colony.base();
		BlockPos toward = null;
		double nearest = Double.MAX_VALUE;
		for (Colony o : Colony.all()) {
			if (o == colony || !o.hasBase() || o.world() != colony.world() || f.met.contains(o.id()) || Factions.loners(o.id())) {
				continue;
			}
			double d = o.base().getSquaredDistance(base);
			if (d < nearest) {
				nearest = d;
				toward = o.base();
			}
		}
		double angle = toward != null ? Math.atan2(toward.getZ() - base.getZ(), toward.getX() - base.getX()) + (RANDOM.nextDouble() - 0.5) * 0.6
			: RANDOM.nextDouble() * Math.PI * 2;
		int dist = 120 + RANDOM.nextInt(80);
		return surface(colony, base.add((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist)));
	}

	/** The open ground at a spot (its loaded surface height, else the base's height). */
	private static BlockPos surface(Colony colony, BlockPos at) {
		var w = colony.world();
		if (w != null && w.isChunkLoaded(at.getX() >> 4, at.getZ() >> 4)) {
			return new BlockPos(at.getX(), w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
		}
		return at;
	}

	public static void clear() {
		NEXT.clear();
	}
}
