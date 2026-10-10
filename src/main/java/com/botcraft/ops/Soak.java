package com.botcraft.ops;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.Botcraft;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.entity.SafeSpot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unattended soak run, for measuring how well companions live on their own. Started by the
 * system property {@code botcraft.soak=<minutes>:<companions>} (see the {@code runSoak} Gradle
 * task): spawns companions in free mode at world spawn with no player online, runs for the given
 * time, then writes {@code soak-report.md} in the run directory and stops the server.
 */
public final class Soak {
	private static final String[] SKINS = {"kai", "ari", "zuri", "noor", "efe", "sunny", "makena", "alex", "steve"};
	private static final String[] TRACKED = {"logs_chopped", "mined_coal", "mined_iron", "mined_copper", "mined_diamond", "mined_gold",
		"mined_redstone", "mined_lapis", "mined_stone", "smelted", "deaths", "stalls"};

	@Nullable
	private static Soak run;

	private final MinecraftServer server;
	private final int minutes;
	private final int count;
	private final long startTick;
	private final long endTick;
	private final List<String> names = new ArrayList<>();
	private final Map<String, Map<String, Integer>> statsAtStart = new HashMap<>();
	private final Map<String, Vec3d> lastPos = new HashMap<>();

	private Soak(MinecraftServer server, int minutes, int count) {
		this.server = server;
		this.minutes = minutes;
		this.count = count;
		this.startTick = server.getTicks();
		this.endTick = startTick + 20L * 60 * minutes;
	}

	/** Called when the server has started: begins a soak run if one was asked for. */
	public static void maybeStart(MinecraftServer server) {
		String spec = System.getProperty("botcraft.soak");
		if (spec == null || spec.isBlank()) {
			return;
		}
		String[] parts = spec.split(":");
		int minutes = Integer.parseInt(parts[0].trim());
		int count = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 4;
		run = new Soak(server, minutes, count);
		SoakStats.active = true;
		run.spawn();
	}

	private void spawn() {
		CompanionManager m = CompanionManager.get();
		ServerWorld world = server.getOverworld();
		BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, world.getSpawnPos());
		BlockPos home = SafeSpot.near(world, top, 8);
		if (home == null) {
			home = top;
		}
		CompanionConfig.get().verboseAiLogging = true;
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		if (colony != null) {
			colony.setBase(home, world.getRegistryKey().getValue().toString());
		}
		for (int i = 1; i <= count; i++) {
			String name = "Soak" + i;
			names.add(name);
			Companion existing = m.byName(name);
			if (existing == null) {
				String err = m.spawn(name, SKINS[(i - 1) % SKINS.length], null, world, Vec3d.ofBottomCenter(home).add(i % 3, 0, i / 3), 0.0F);
				if (err != null) {
					Botcraft.LOG.warn("Soak: couldn't spawn {}: {}", name, err);
					continue;
				}
			}
			Companion c = m.byName(name);
			c.data.mode = "free";
			c.data.owner = null;
			c.memory().setPlace("home", home, c.dimension(), world.getTime());
			statsAtStart.put(name, new HashMap<>(c.memory().stats));
		}
		Botcraft.LOG.info("Soak: {} companions in free mode for {} minutes, home {}", names.size(), minutes, home.toShortString());
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		if (run == null || run.server != server) {
			return;
		}
		run.onTick();
	}

	private void onTick() {
		long now = server.getTicks();
		if (now % 20 == 0) {
			CompanionManager m = CompanionManager.get();
			for (String name : names) {
				Companion c = m == null ? null : m.byName(name);
				CompanionPlayer p = c == null ? null : c.player();
				if (p == null) {
					continue;
				}
				Vec3d before = lastPos.put(name, p.getPos());
				if (before != null && before.squaredDistanceTo(p.getPos()) < 30 * 30) { // ignore respawns and teleports
					SoakStats.moved(name, before.distanceTo(p.getPos()));
				}
			}
		}
		if (now % (20 * 60) == 0) {
			Botcraft.LOG.info("Soak: {} of {} minutes", (now - startTick) / 1200, minutes);
		}
		if (now >= endTick) {
			finish();
		}
	}

	private void finish() {
		Path report = server.getRunDirectory().toPath().resolve("soak-report.md");
		String text = report();
		try {
			Files.writeString(report, text);
		} catch (IOException e) {
			Botcraft.LOG.error("Soak: couldn't write {}", report, e);
		}
		Botcraft.LOG.info("Soak finished; report in {}\n{}", report.toAbsolutePath(), text);
		run = null;
		SoakStats.active = false;
		server.stop(false);
	}

	private String report() {
		StringBuilder sb = new StringBuilder();
		double hours = minutes / 60.0;
		sb.append("# Soak report\n\n");
		sb.append(String.format("%d companions, free mode, %d minutes (%s), no players online.%n%n", names.size(), minutes,
			server.getSaveProperties().getLevelName()));
		int deaths = SoakStats.deaths.values().stream().mapToInt(Integer::intValue).sum();
		int stalls = SoakStats.stalls.values().stream().mapToInt(Integer::intValue).sum();
		int done = SoakStats.jobsDone.values().stream().mapToInt(Integer::intValue).sum();
		int failed = SoakStats.jobsFailed.values().stream().mapToInt(Integer::intValue).sum();
		sb.append("## Summary\n\n");
		sb.append(String.format("| Metric | Value |%n|---|---|%n"));
		sb.append(String.format("| Deaths | %d (%.1f per companion-hour) |%n", deaths, deaths / Math.max(0.01, hours * names.size())));
		sb.append(String.format("| Stalls (watchdog) | %d |%n", stalls));
		sb.append(String.format("| Jobs finished / failed | %d / %d |%n", done, failed));
		double avgMs = SoakStats.ticks == 0 ? 0 : SoakStats.tickNanos / 1e6 / SoakStats.ticks;
		sb.append(String.format("| AI time per tick (all companions) | avg %.2f ms, max %.1f ms, over budget %d ticks |%n", avgMs,
			SoakStats.maxTickNanos / 1e6, SoakStats.ticksOverBudget));
		sb.append(String.format("| AI budget per tick | avg %.2f ms |%n", SoakStats.ticks == 0 ? 0.0 : SoakStats.budgetSum / SoakStats.ticks));
		sb.append(String.format("| Server tick time (whole server, smoothed) | avg %.1f ms, over 50 ms on %d ticks |%n",
			SoakStats.ticks == 0 ? 0.0 : SoakStats.serverMsSum / SoakStats.ticks, SoakStats.serverSlowTicks));
		sb.append(String.format("| Loaded chunks at the end | %d |%n", server.getOverworld().getChunkManager().getLoadedChunkCount()));
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		int inChests = colony == null ? 0 : colony.stock().values().stream().mapToInt(Integer::intValue).sum();
		sb.append(String.format("| Items stored at the base | %d put away; %d in %d chest(s) at the end |%n%n", SoakStats.stored, inChests,
			colony == null ? 0 : colony.chests().size()));

		sb.append("## Companions\n\n");
		sb.append("| Name | Deaths | Walked | Logs | Coal | Iron | Diamonds | Smelted | Hunted | Pickaxe | Sword | Armour | Food | Hunger |\n");
		sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		CompanionManager m = CompanionManager.get();
		for (String name : names) {
			Companion c = m == null ? null : m.byName(name);
			if (c == null) {
				continue;
			}
			Map<String, Integer> start = statsAtStart.getOrDefault(name, Map.of());
			Map<String, Integer> gained = new LinkedHashMap<>();
			for (String k : TRACKED) {
				gained.put(k, c.memory().stats.getOrDefault(k, 0) - start.getOrDefault(k, 0));
			}
			CompanionPlayer p = c.player();
			PlayerInventory inv = p == null ? null : p.getInventory();
			int hunted = 0;
			for (var e : c.memory().stats.entrySet()) {
				if (e.getKey().startsWith("hunted_")) {
					hunted += e.getValue() - start.getOrDefault(e.getKey(), 0);
				}
			}
			sb.append(String.format("| %s | %d | %.0f | %d | %d | %d | %d | %d | %d | %s | %s | %s | %d | %s |%n", name,
				SoakStats.deaths.getOrDefault(name, 0), SoakStats.distance.getOrDefault(name, 0.0),
				gained.get("logs_chopped"), gained.get("mined_coal"), gained.get("mined_iron"), gained.get("mined_diamond"), gained.get("smelted"), hunted,
				inv == null ? "-" : best(inv, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE),
				inv == null ? "-" : best(inv, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD),
				p == null ? "-" : armour(p), inv == null ? 0 : Inventories.foodCount(inv),
				p == null ? "-" : p.getHungerManager().getFoodLevel() + "/20"));
		}
		sb.append('\n');
		if (colony != null && !colony.stock().isEmpty()) {
			Map<String, Integer> stock = new LinkedHashMap<>();
			colony.stock().forEach((item, n) -> stock.put(com.botcraft.knowledge.ItemNames.id(item), n));
			section(sb, "Base stock", stock);
		}
		navigation(sb);
		section(sb, "Deaths by cause", SoakStats.deathCauses);
		section(sb, "Failed jobs (task: reason)", SoakStats.failReasons);
		section(sb, "Stalls", SoakStats.stalls);
		section(sb, "Failsafe rescues (free mode, sent home)", SoakStats.rescues);
		section(sb, "Finished jobs", SoakStats.jobsDone);
		section(sb, "Interrupts", SoakStats.interrupts);
		section(sb, "Request board", SoakStats.board);
		return sb.toString();
	}

	private static void navigation(StringBuilder sb) {
		sb.append("## Navigation\n\n");
		List<Integer> lat = new ArrayList<>(SoakStats.searchTicks);
		lat.sort(null);
		int n = lat.size();
		sb.append(String.format("| Metric | Value |%n|---|---|%n"));
		sb.append(String.format("| Path searches | %d (%.0f nodes each on average) |%n", n, n == 0 ? 0.0 : (double) SoakStats.searchNodes / n));
		sb.append(String.format("| Search time in ticks | median %d, p90 %d, p99 %d, max %d |%n", pct(lat, 50), pct(lat, 90), pct(lat, 99),
			n == 0 ? 0 : lat.get(n - 1)));
		sb.append(String.format("| Companion-ticks spent waiting on a search | %d, of which %d got no nodes (budget used up) |%n",
			SoakStats.searchingTicks, SoakStats.starvedTicks));
		sb.append(String.format("| Search CPU | %.2f ms per server tick on average |%n",
			SoakStats.ticks == 0 ? 0.0 : SoakStats.searchNanos / 1e6 / SoakStats.ticks));
		int stuck = SoakStats.stuck.values().stream().mapToInt(Integer::intValue).sum();
		int fails = SoakStats.navFails.values().stream().mapToInt(Integer::intValue).sum();
		sb.append(String.format("| Stuck events / navigation failures | %d / %d |%n%n", stuck, fails));
		section(sb, "Search outcomes", SoakStats.searches);
		section(sb, "Stuck while following a path", SoakStats.stuck);
		section(sb, "Navigation failures", SoakStats.navFails);
		section(sb, "Other navigation events", SoakStats.navEvents);
	}

	private static int pct(List<Integer> sorted, int p) {
		return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
	}

	private static void section(StringBuilder sb, String title, Map<String, Integer> counts) {
		sb.append("## ").append(title).append("\n\n");
		if (counts.isEmpty()) {
			sb.append("None.\n\n");
			return;
		}
		counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(e -> sb.append("- ").append(e.getValue()).append(" × ").append(e.getKey()).append('\n'));
		sb.append('\n');
	}

	private static String best(PlayerInventory inv, Item... tiers) {
		String best = "none";
		for (Item t : tiers) {
			if (Inventories.count(inv, s -> s.isOf(t)) > 0) {
				best = com.botcraft.knowledge.ItemNames.id(t).replaceAll("_(pickaxe|sword)$", "");
			}
		}
		return best;
	}

	private static String armour(CompanionPlayer p) {
		int pieces = 0;
		for (ItemStack s : p.getArmorItems()) {
			if (s.getItem() instanceof ArmorItem) {
				pieces++;
			}
		}
		return pieces + "/4";
	}
}
