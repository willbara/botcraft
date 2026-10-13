package com.botcraft.ops;

import com.botcraft.Botcraft;
import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.TickBudget;
import com.google.gson.GsonBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A standard task run from scratch, timed step by step: one companion with an empty inventory,
 * on a fixed-seed world, at one of a few fixed start points, told to get (say) a diamond
 * pickaxe. Every milestone on the way (first log, crafting table, wooden pickaxe, ..., diamond)
 * is timed, with what went wrong in between (stuck, failed searches, failed jobs, deaths). Runs
 * of two builds compare phase by phase ({@code tools/bench.sh}, {@code tools/bench-compare.py}).
 *
 * <p>Started by {@code -Dbotcraft.bench=<goal>:<minutes>:<trial>}. Path search is limited by
 * node counts only (not by the clock), so a run doesn't depend on how busy the machine is.
 * Mobs and the world's own randomness still differ between runs, hence several trials.
 */
public final class Bench {
	/** What a run sets out to get, and the job that's ordered for it. */
	private static final Map<String, String> GOALS = Map.of(
		"diamond_pickaxe", "gear diamond_pickaxe",
		"iron_pickaxe", "gear iron_pickaxe",
		"stone_pickaxe", "gear stone_pickaxe");

	/** Start points, relative to world spawn, one per trial (in turn). */
	private static final int[][] STARTS = {{0, 0}, {160, 0}, {0, 160}, {-160, 0}, {0, -160}, {160, 160}, {-160, -160}, {160, -160}};

	private record Milestone(String key, String label, Predicate<CompanionPlayer> reached) {
	}

	private static final List<Milestone> MILESTONES = List.of(
		new Milestone("log", "first log", p -> got(p, s -> s.isIn(ItemTags.LOGS), Items.OAK_LOG)),
		new Milestone("table", "crafting table", p -> got(p, s -> s.isOf(Items.CRAFTING_TABLE), Items.CRAFTING_TABLE)),
		new Milestone("wooden_pickaxe", "wooden pickaxe", p -> got(p, s -> s.isOf(Items.WOODEN_PICKAXE), Items.WOODEN_PICKAXE)),
		new Milestone("stone", "first stone", p -> got(p, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE), Items.COBBLESTONE)),
		new Milestone("stone_pickaxe", "stone pickaxe", p -> got(p, s -> s.isOf(Items.STONE_PICKAXE), Items.STONE_PICKAXE)),
		new Milestone("coal", "coal", p -> got(p, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL), Items.COAL)),
		new Milestone("furnace", "furnace", p -> got(p, s -> s.isOf(Items.FURNACE), Items.FURNACE)),
		new Milestone("raw_iron", "raw iron", p -> got(p, s -> s.isOf(Items.RAW_IRON), Items.RAW_IRON)),
		new Milestone("iron_ingot", "iron ingot", p -> got(p, s -> s.isOf(Items.IRON_INGOT), Items.IRON_INGOT)),
		new Milestone("iron_pickaxe", "iron pickaxe", p -> got(p, s -> s.isOf(Items.IRON_PICKAXE), Items.IRON_PICKAXE)),
		new Milestone("deep", "below y 0", p -> p.getY() < 0),
		new Milestone("diamond", "diamond", p -> got(p, s -> s.isOf(Items.DIAMOND), Items.DIAMOND)),
		new Milestone("diamond_pickaxe", "diamond pickaxe", p -> got(p, s -> s.isOf(Items.DIAMOND_PICKAXE), Items.DIAMOND_PICKAXE)));

	@Nullable
	private static Bench run;

	private final MinecraftServer server;
	private final String goal;
	private final String job;
	private final int minutes;
	private final int trial;
	private final long startTick;
	private final long endTick;
	private final List<Milestone> wanted;
	private final Map<String, Long> reached = new LinkedHashMap<>();
	private final List<Map<String, Object>> phases = new ArrayList<>();
	private Map<String, Long> countersAtLast;
	private BlockPos start = BlockPos.ORIGIN;
	private int reorders;
	private long lastIdleCheck;
	@Nullable
	private Vec3d lastPos;

	private Bench(MinecraftServer server, String goal, int minutes, int trial) {
		this.server = server;
		this.goal = goal;
		this.job = GOALS.get(goal);
		this.minutes = minutes;
		this.trial = trial;
		this.startTick = server.getTicks();
		this.endTick = startTick + 20L * 60 * minutes;
		List<Milestone> list = new ArrayList<>();
		for (Milestone m : MILESTONES) {
			list.add(m);
			if (m.key().equals(goal)) {
				break;
			}
		}
		// A goal short of diamonds doesn't need the trip down.
		if (!goal.equals("diamond_pickaxe")) {
			list.removeIf(m -> m.key().equals("deep") || m.key().equals("diamond"));
		}
		this.wanted = list;
		this.countersAtLast = counters();
	}

	/** Called when the server has started: begins a bench run if one was asked for. */
	public static void maybeStart(MinecraftServer server) {
		String spec = System.getProperty("botcraft.bench");
		if (spec == null || spec.isBlank()) {
			return;
		}
		String[] parts = spec.split(":");
		String goal = parts[0].trim();
		if (!GOALS.containsKey(goal)) {
			Botcraft.LOG.error("Bench: unknown goal {} (one of {})", goal, GOALS.keySet());
			return;
		}
		int minutes = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 30;
		int trial = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 1;
		TickBudget.unlimited = true;
		SoakStats.active = true;
		run = new Bench(server, goal, minutes, trial);
		run.spawn();
	}

	private void spawn() {
		CompanionManager m = CompanionManager.get();
		ServerWorld world = server.getOverworld();
		int[] off = STARTS[(trial - 1) % STARTS.length];
		BlockPos spawn = world.getSpawnPos().add(off[0], 0, off[1]);
		world.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4); // generate it before looking for ground
		BlockPos ground = Soak.groundNear(world, spawn, 24);
		start = ground != null ? ground : world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawn);
		CompanionConfig.get().verboseAiLogging = true;
		String err = m.spawn("Bench", "steve", null, world, Vec3d.ofBottomCenter(start), 0.0F);
		if (err != null) {
			Botcraft.LOG.error("Bench: couldn't spawn: {}", err);
			return;
		}
		Companion c = m.byName("Bench");
		c.player().getInventory().clear();
		c.data.owner = null;
		c.jobs().replace(List.of(job));
		Botcraft.LOG.info("Bench: trial {} of {} from {} ({} minutes at most)", trial, goal, start.toShortString(), minutes);
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		if (run != null && run.server == server) {
			run.onTick();
		}
	}

	private void onTick() {
		long now = server.getTicks();
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName("Bench");
		CompanionPlayer p = c == null ? null : c.player();
		if (p != null && p.isAlive()) {
			if (now % 20 == 0) {
				if (lastPos != null && lastPos.squaredDistanceTo(p.getPos()) < 30 * 30) { // not respawns
					SoakStats.moved("Bench", lastPos.distanceTo(p.getPos()));
				}
				lastPos = p.getPos();
			}
			for (Milestone ms : wanted) {
				if (!reached.containsKey(ms.key()) && ms.reached().test(p)) {
					reach(ms, now);
				}
			}
			// The order failed (and was given up on): give it again, as a player would.
			if (now - lastIdleCheck >= 40) {
				lastIdleCheck = now;
				if (c.jobs().isIdle() && !reached.containsKey(goal)) {
					reorders++;
					Botcraft.LOG.info("Bench: ordering {} again ({})", job, reorders);
					c.jobs().replace(List.of(job));
				}
			}
		}
		if (reached.containsKey(goal) || now >= endTick) {
			finish(now);
		}
	}

	private void reach(Milestone ms, long now) {
		reached.put(ms.key(), now - startTick);
		Map<String, Long> counters = counters();
		Map<String, Object> phase = new LinkedHashMap<>();
		phase.put("to", ms.key());
		phase.put("ticks", now - startTick - (phases.isEmpty() ? 0 : ((Number) phases.get(phases.size() - 1).get("at")).longValue()));
		phase.put("at", now - startTick);
		counters.forEach((k, v) -> phase.put(k, v - countersAtLast.getOrDefault(k, 0L)));
		phases.add(phase);
		countersAtLast = counters;
		Botcraft.LOG.info("Bench: {} at {}", ms.label(), clock(now - startTick));
	}

	/** Running totals from SoakStats, for what happened between milestones. */
	private static Map<String, Long> counters() {
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("stuck", sum(SoakStats.stuck));
		out.put("navFails", sum(SoakStats.navFails));
		out.put("searches", sum(SoakStats.searches));
		out.put("escapeSearches", SoakStats.searches.entrySet().stream().filter(e -> e.getKey().contains("escape"))
			.mapToLong(Map.Entry::getValue).sum());
		out.put("nodes", SoakStats.searchNodes);
		out.put("jobsFailed", sum(SoakStats.jobsFailed));
		out.put("deaths", sum(SoakStats.deaths));
		out.put("interrupts", sum(SoakStats.interrupts));
		out.put("walked", Math.round(SoakStats.distance.values().stream().mapToDouble(Double::doubleValue).sum()));
		return out;
	}

	private static long sum(Map<String, Integer> m) {
		return m.values().stream().mapToLong(Integer::longValue).sum();
	}

	/** In the inventory now, or ever picked up or crafted (a table can be placed the tick it's made). */
	private static boolean got(CompanionPlayer p, Predicate<ItemStack> which, Item item) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (!inv.getStack(i).isEmpty() && which.test(inv.getStack(i))) {
				return true;
			}
		}
		var stats = p.getStatHandler();
		return stats.getStat(Stats.PICKED_UP.getOrCreateStat(item)) > 0 || stats.getStat(Stats.CRAFTED.getOrCreateStat(item)) > 0;
	}

	private void finish(long now) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("goal", goal);
		out.put("trial", trial);
		out.put("seed", server.getSaveProperties().getGeneratorOptions().getSeed());
		out.put("start", new int[]{start.getX(), start.getY(), start.getZ()});
		out.put("done", reached.containsKey(goal));
		out.put("ticks", now - startTick);
		out.put("reorders", reorders);
		out.put("reached", reached);
		out.put("phases", phases);
		out.put("totals", counters());
		out.put("failReasons", new LinkedHashMap<>(SoakStats.failReasons));
		out.put("deathCauses", new LinkedHashMap<>(SoakStats.deathCauses));
		out.put("interrupts", new LinkedHashMap<>(SoakStats.interrupts));
		Path dir = server.getRunDirectory().toPath();
		try {
			Files.writeString(dir.resolve("bench.json"), new GsonBuilder().setPrettyPrinting().create().toJson(out));
			Files.writeString(dir.resolve("bench-report.md"), report(now));
		} catch (IOException e) {
			Botcraft.LOG.error("Bench: couldn't write the report", e);
		}
		Botcraft.LOG.info("Bench finished: {} {} in {}", goal, reached.containsKey(goal) ? "done" : "not done", clock(now - startTick));
		run = null;
		SoakStats.active = false;
		server.stop(false);
	}

	private String report(long now) {
		StringBuilder sb = new StringBuilder();
		sb.append("# Bench: ").append(goal).append(", trial ").append(trial).append("\n\n");
		sb.append(String.format("Start %s, seed %d. %s in %s (at most %d minutes), ordered %d time(s).%n%n", start.toShortString(),
			server.getSaveProperties().getGeneratorOptions().getSeed(), reached.containsKey(goal) ? "Done" : "Not done", clock(now - startTick),
			minutes, reorders + 1));
		sb.append("| Milestone | At | Took | Stuck | Nav fails | Searches | Nodes | Escape | Jobs failed | Deaths |\n");
		sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
		for (Map<String, Object> ph : phases) {
			String label = MILESTONES.stream().filter(m -> m.key().equals(ph.get("to"))).findFirst().map(Milestone::label).orElse("?");
			sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n", label, clock((Long) ph.get("at")),
				clock((Long) ph.get("ticks")), ph.get("stuck"), ph.get("navFails"), ph.get("searches"), ph.get("nodes"), ph.get("escapeSearches"),
				ph.get("jobsFailed"), ph.get("deaths")));
		}
		for (Milestone ms : wanted) {
			if (!reached.containsKey(ms.key())) {
				sb.append("| ").append(ms.label()).append(" | not reached | | | | | | | | |\n");
			}
		}
		sb.append("\nJob failures: ").append(SoakStats.failReasons.isEmpty() ? "none" : SoakStats.failReasons).append('\n');
		sb.append("Deaths: ").append(SoakStats.deathCauses.isEmpty() ? "none" : SoakStats.deathCauses).append('\n');
		sb.append("Interrupts: ").append(SoakStats.interrupts.isEmpty() ? "none" : SoakStats.interrupts).append('\n');
		return sb.toString();
	}

	private static String clock(long ticks) {
		long s = ticks / 20;
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
