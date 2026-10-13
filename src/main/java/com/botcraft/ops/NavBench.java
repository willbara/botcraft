package com.botcraft.ops;

import com.botcraft.Botcraft;
import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.entity.SafeSpot;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.BlockState;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The navigation bench: one companion walks a fixed list of start/goal pairs on a fixed-seed
 * world, one after the other, and each trip is scored. Success means it arrived; for every trip
 * the ticks taken, the distance walked against the straight line, the path searches (count,
 * nodes, ticks spent waiting on them), stuck events, and the AI time per server tick are kept.
 *
 * <p>Started by {@code -Dbotcraft.navbench=<pairs file>:<worker>:<workers>:<minutes>}: this
 * server runs the pairs whose index modulo {@code workers} picks {@code worker} (1-based), so
 * several servers can share the list (see {@code tools/navbench.sh}). The pairs file is made
 * once with {@code -Dbotcraft.navbench=generate:<count>:<seed>} and lives in the repository
 * ({@code tools/navbench-pairs.json}), so it is the same set every time and can be edited: add
 * a pair where a companion was seen to fail, and it stays in the set.
 *
 * <p>The companion carries a netherite pickaxe, cobblestone and food, as a working companion would,
 * so digging, bridging and pillaring are all on the table. The tick budget is the normal one (not
 * unlimited as in {@link Bench}), so the AI time per tick is what a real server would see.
 */
public final class NavBench {
	/** One trip: feet positions, the radius that counts as arrived, and a word for the report. */
	public record Pair(int[] start, int[] goal, double radius, String tag) {
	}

	private record Trip(int index, Pair pair, String outcome, int ticks, double walked, double straight, long nodes, int searches,
						long searchTicks, int stuck, double aiMsPerTick, double aiMsMax, double serverMsAvg, double serverMsMax, int jumps, String detail) {
	}

	@Nullable
	private static NavBench run;

	private final MinecraftServer server;
	private final List<Pair> pairs;
	private final List<Integer> mine = new ArrayList<>();
	private final String label;
	private final long endTick;
	private final List<Trip> trips = new ArrayList<>();
	private int at = -1;
	private int phase; // 0 = settle after teleport, 1 = walking, 3 = waiting for a respawn
	private long phaseStart;
	private long tripStart;
	private int tripLimit;
	private double walked;
	@Nullable
	private Vec3d lastPos;
	private double serverMsSum;
	private double serverMsMax;
	private long aiNanosMax;
	private int serverSamples;
	private long nodesAt, searchTicksAt, aiNanosAt;
	private int searchesAt, stuckAt, jumpsAt;

	private NavBench(MinecraftServer server, List<Pair> pairs, int worker, int workers, int minutes, String label) {
		this.server = server;
		this.pairs = pairs;
		this.label = label;
		for (int i = 0; i < pairs.size(); i++) {
			if (i % workers == worker - 1) {
				mine.add(i);
			}
		}
		this.endTick = server.getTicks() + 20L * 60 * minutes;
	}

	/** Called when the server has started: generates pairs or begins a run if one was asked for. */
	public static void maybeStart(MinecraftServer server) {
		String spec = System.getProperty("botcraft.navbench");
		if (spec == null || spec.isBlank()) {
			return;
		}
		String[] parts = spec.split(":");
		try {
			if (parts[0].equals("generate")) {
				int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 100;
				long seed = parts.length > 2 ? Long.parseLong(parts[2]) : 12345;
				generate(server, count, seed);
				server.stop(false);
				return;
			}
			List<Pair> pairs = new Gson().fromJson(Files.readString(Path.of(parts[0])), new TypeToken<List<Pair>>() {
			}.getType());
			int worker = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
			int workers = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
			int minutes = parts.length > 3 ? Integer.parseInt(parts[3]) : 60;
			SoakStats.active = true;
			run = new NavBench(server, pairs, worker, workers, minutes, "w" + worker);
			run.spawn();
		} catch (IOException | RuntimeException e) {
			Botcraft.LOG.error("NavBench: bad spec {}", spec, e);
			server.stop(false);
		}
	}

	// ---- pair generation -------------------------------------------------------------------------

	/**
	 * A fixed set of trips around spawn, from a seeded random: half across the surface (32 to
	 * 128 blocks), a quarter from the surface down into a cave, the rest between caves or from a
	 * cave back up to the surface. Written to {@code navbench-pairs.json} in the run directory.
	 */
	private static void generate(MinecraftServer server, int count, long seed) throws IOException {
		ServerWorld world = server.getOverworld();
		Random random = new Random(seed);
		BlockPos spawn = world.getSpawnPos();
		List<Pair> out = new ArrayList<>();
		int guard = 0;
		while (out.size() < count && guard++ < count * 40) {
			int i = out.size();
			String tag = i < count / 2 ? "surface" : i < count * 3 / 4 ? "down" : i < count * 9 / 10 ? "up" : "cave";
			double angle = random.nextDouble() * Math.PI * 2;
			int dist = 32 + random.nextInt(97);
			BlockPos a = spawn.add(random.nextInt(513) - 256, 0, random.nextInt(513) - 256);
			BlockPos b = a.add((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
			BlockPos start = tag.equals("surface") || tag.equals("down") ? surface(world, a) : cave(world, a, random);
			BlockPos goal = tag.equals("surface") || tag.equals("up") ? surface(world, b) : cave(world, b, random);
			if (start == null || goal == null || start.getSquaredDistance(goal) < 20 * 20) {
				continue;
			}
			out.add(new Pair(new int[]{start.getX(), start.getY(), start.getZ()}, new int[]{goal.getX(), goal.getY(), goal.getZ()}, 1.5, tag));
			Botcraft.LOG.info("NavBench: pair {} {} {} -> {}", i, tag, start.toShortString(), goal.toShortString());
		}
		Path file = server.getRunDirectory().toPath().resolve("navbench-pairs.json");
		Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out));
		Botcraft.LOG.info("NavBench: wrote {} pairs to {}", out.size(), file);
	}

	@Nullable
	private static BlockPos surface(ServerWorld world, BlockPos near) {
		world.getChunk(near.getX() >> 4, near.getZ() >> 4);
		for (int r = 0; r <= 6; r += 2) {
			for (int dx = -r; dx <= r; dx += 2) {
				for (int dz = -r; dz <= r; dz += 2) {
					int x = near.getX() + dx;
					int z = near.getZ() + dz;
					world.getChunk(x >> 4, z >> 4);
					BlockPos feet = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
					if (feet.getY() > world.getSeaLevel() && SafeSpot.ok(world, feet) && world.getFluidState(feet.down()).isEmpty()) {
						return feet;
					}
				}
			}
		}
		return null;
	}

	/** A cave floor under {@code near}: air with air above and solid below, at least 8 below the surface. */
	@Nullable
	private static BlockPos cave(ServerWorld world, BlockPos near, Random random) {
		for (int tries = 0; tries < 12; tries++) {
			int x = near.getX() + random.nextInt(17) - 8;
			int z = near.getZ() + random.nextInt(17) - 8;
			world.getChunk(x >> 4, z >> 4);
			int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos.Mutable m = new BlockPos.Mutable(x, 0, z);
			for (int y = top - 8; y > world.getBottomY() + 8; y--) {
				BlockState here = world.getBlockState(m.setY(y));
				if (!here.isAir()) {
					continue;
				}
				BlockState above = world.getBlockState(m.setY(y + 1));
				BlockState below = world.getBlockState(m.setY(y - 1));
				if (above.isAir() && below.isSideSolidFullSquare(world, m, net.minecraft.util.math.Direction.UP) && below.getFluidState().isEmpty()
					&& world.getFluidState(m.setY(y)).isEmpty()) {
					BlockPos feet = new BlockPos(x, y, z);
					if (SafeSpot.ok(world, feet)) {
						return feet;
					}
				}
			}
		}
		return null;
	}

	// ---- running -----------------------------------------------------------------------------------

	private void spawn() {
		CompanionManager m = CompanionManager.get();
		ServerWorld world = server.getOverworld();
		CompanionConfig.get().verboseAiLogging = true;
		Pair first = pairs.get(mine.get(0));
		BlockPos start = new BlockPos(first.start()[0], first.start()[1], first.start()[2]);
		world.getChunk(start.getX() >> 4, start.getZ() >> 4);
		String err = m.spawn("NavBench", "steve", null, world, Vec3d.ofBottomCenter(start), 0.0F);
		if (err != null) {
			Botcraft.LOG.error("NavBench: couldn't spawn: {}", err);
			server.stop(false);
			return;
		}
		Companion c = m.byName("NavBench");
		com.botcraft.chat.CommandTasks.stay(c);
		c.data.owner = null;
		Botcraft.LOG.info("NavBench: {} of {} pairs ({})", mine.size(), pairs.size(), label);
		next();
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		if (run != null && run.server == server) {
			run.onTick();
		}
	}

	private void onTick() {
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName("NavBench");
		CompanionPlayer p = c == null ? null : c.player();
		long now = server.getTicks();
		if (now >= endTick) {
			if (at >= 0 && at < mine.size() && phase == 1) {
				record("timeout (run over)", c);
			}
			finish();
			return;
		}
		if (p == null) {
			if (c != null && phase == 1) {
				record("died", c);
				phase = 3; // on to the next trip once it has respawned (retrying the same one looped)
			}
			return;
		}
		if (phase == 3) {
			next();
			return;
		}
		Pair pair = pairs.get(mine.get(at));
		BlockPos goal = new BlockPos(pair.goal()[0], pair.goal()[1], pair.goal()[2]);
		if (phase == 0) {
			// Let the chunks around the start load and the companion settle on the ground. Until its
			// region is loaded a companion doesn't move at all (CompanionPlayer#tick), so starting
			// earlier scored trips the companion was frozen for.
			BlockPos start = new BlockPos(pair.start()[0], pair.start()[1], pair.start()[2]);
			if (p.getBlockPos().getSquaredDistance(start) > 2 * 2 && now - phaseStart < 600) {
				p.teleport(server.getOverworld(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
			}
			server.getOverworld().getChunkManager().updatePosition(p);
			boolean ready = now - phaseStart >= 40 && !p.isRegionUnloaded() && p.isOnGround()
				&& server.getOverworld().isChunkLoaded(goal.getX() >> 4, goal.getZ() >> 4);
			if (ready || now - phaseStart >= 600) {
				begin(c, p, pair, goal);
			}
			return;
		}
		long[] lengths = server.lastTickLengths;
		double ms = lengths[(int) ((now - 1 + lengths.length) % lengths.length)] / 1e6;
		serverMsSum += ms;
		serverMsMax = Math.max(serverMsMax, ms);
		serverSamples++;
		aiNanosMax = Math.max(aiNanosMax, SoakStats.lastTickNanos);
		if (lastPos != null) {
			walked += lastPos.distanceTo(p.getPos());
		}
		lastPos = p.getPos();
		Navigator.State s = c.nav().state();
		int ticks = (int) (now - tripStart);
		if (s == Navigator.State.ARRIVED) {
			record("arrived", c);
			next();
		} else if (s == Navigator.State.FAILED) {
			record("failed: " + c.nav().failReason(), c);
			next();
		} else if (s == Navigator.State.IDLE) {
			// Something else (an interrupt) stopped the navigator: set the goal again.
			c.nav().moveTo(Goal.near(goal, pair.radius()), true);
		} else if (ticks > tripLimit) {
			record("timeout", c);
			next();
		}
	}

	private void begin(Companion c, CompanionPlayer p, Pair pair, BlockPos goal) {
		phase = 1;
		tripStart = server.getTicks();
		double straight = Math.sqrt(p.getBlockPos().getSquaredDistance(goal));
		tripLimit = (int) Math.min(3600, 600 + 20 * straight);
		walked = 0;
		lastPos = p.getPos();
		serverMsSum = 0;
		serverMsMax = 0;
		serverSamples = 0;
		aiNanosMax = 0;
		nodesAt = SoakStats.searchNodes;
		searchTicksAt = SoakStats.searchingTicks;
		aiNanosAt = SoakStats.tickNanos;
		searchesAt = SoakStats.searches.values().stream().mapToInt(Integer::intValue).sum();
		stuckAt = SoakStats.stuck.values().stream().mapToInt(Integer::intValue).sum();
		jumpsAt = c.actions().jumps;
		c.nav().moveTo(Goal.near(goal, pair.radius()), true);
		Botcraft.LOG.info("NavBench: trip {} ({}) {} -> {}, {} blocks", mine.get(at), pair.tag(), p.getBlockPos().toShortString(), goal.toShortString(),
			Math.round(straight));
	}

	private void record(String outcome, @Nullable Companion c) {
		Pair pair = pairs.get(mine.get(at));
		BlockPos start = new BlockPos(pair.start()[0], pair.start()[1], pair.start()[2]);
		BlockPos goal = new BlockPos(pair.goal()[0], pair.goal()[1], pair.goal()[2]);
		int ticks = (int) (server.getTicks() - tripStart);
		int searches = SoakStats.searches.values().stream().mapToInt(Integer::intValue).sum() - searchesAt;
		int stuck = SoakStats.stuck.values().stream().mapToInt(Integer::intValue).sum() - stuckAt;
		double aiMs = ticks == 0 ? 0 : (SoakStats.tickNanos - aiNanosAt) / 1e6 / ticks;
		String detail = c == null || c.player() == null ? "" : "ended at " + c.player().getBlockPos().toShortString() + pickaxeLeft(c.player());
		Trip t = new Trip(mine.get(at), pair, outcome, ticks, walked, Math.sqrt(start.getSquaredDistance(goal)), SoakStats.searchNodes - nodesAt, searches,
			SoakStats.searchingTicks - searchTicksAt, stuck, aiMs, aiNanosMax / 1e6, serverSamples == 0 ? 0 : serverMsSum / serverSamples, serverMsMax,
			c == null ? 0 : c.actions().jumps - jumpsAt, detail);
		trips.add(t);
		Botcraft.LOG.info("NavBench: trip {} {} in {} ticks, walked {} for {} straight, {} nodes in {} searches, {} stuck; {}", t.index(), outcome, ticks,
			Math.round(walked), Math.round(t.straight()), t.nodes(), searches, stuck, detail);
	}

	/** ", pickaxe 1990/2031 left", or ", no pickaxe" if it broke. */
	private static String pickaxeLeft(CompanionPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(Items.NETHERITE_PICKAXE) || s.isOf(Items.STONE_PICKAXE)) {
				return ", pickaxe " + (s.getMaxDamage() - s.getDamage()) + "/" + s.getMaxDamage() + " left";
			}
		}
		return ", no pickaxe";
	}

	private void next() {
		if (++at >= mine.size()) {
			finish();
			return;
		}
		CompanionManager m = CompanionManager.get();
		Companion c = m.byName("NavBench");
		CompanionPlayer p = c.player();
		if (p == null) {
			at--; // dead: this trip hasn't started; set it up once the companion is back
			phase = 3;
			return;
		}
		Pair pair = pairs.get(mine.get(at));
		c.nav().stop();
		c.brain().clearAll();
		c.jobs().clear();
		p.getInventory().clear();
		// Netherite, as in the manual Baritone runs: with stone, long digs broke the pickaxe partway
		// (131 uses) and the rest was dug by hand, so the bench measured the tool, not the pathing.
		// Efficiency V only makes the runs quicker (the manual runs used an enchanted pickaxe too).
		ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
		pick.addEnchantment(net.minecraft.enchantment.Enchantments.EFFICIENCY, 5);
		p.getInventory().insertStack(pick);
		p.getInventory().insertStack(new ItemStack(Items.COBBLESTONE, 32));
		p.getInventory().insertStack(new ItemStack(Items.BREAD, 8));
		p.setHealth(p.getMaxHealth());
		p.getHungerManager().setFoodLevel(20);
		p.setAir(p.getMaxAir());
		p.fallDistance = 0;
		p.setVelocity(Vec3d.ZERO);
		ServerWorld world = server.getOverworld();
		world.getChunk(pair.start()[0] >> 4, pair.start()[2] >> 4);
		p.teleport(world, pair.start()[0] + 0.5, pair.start()[1], pair.start()[2] + 0.5, 0.0F, 0.0F);
		phase = 0;
		phaseStart = server.getTicks();
	}

	private void finish() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("label", label);
		out.put("seed", server.getSaveProperties().getGeneratorOptions().getSeed());
		out.put("trips", trips);
		Path dir = server.getRunDirectory().toPath();
		try {
			Files.writeString(dir.resolve("navbench.json"), new GsonBuilder().setPrettyPrinting().create().toJson(out));
			Files.writeString(dir.resolve("navbench-report.md"), report());
		} catch (IOException e) {
			Botcraft.LOG.error("NavBench: couldn't write the report", e);
		}
		Botcraft.LOG.info("NavBench finished: {} trips", trips.size());
		run = null;
		SoakStats.active = false;
		server.stop(false);
	}

	private String report() {
		StringBuilder sb = new StringBuilder();
		long ok = trips.stream().filter(t -> t.outcome().equals("arrived")).count();
		sb.append("# Navigation bench ").append(label).append("\n\n");
		sb.append(String.format("%d of %d trips arrived.%n%n", ok, trips.size()));
		sb.append("| # | Tag | Straight | Outcome | Ticks | Walked | Ratio | Searches | Nodes | Search ticks | Stuck | Jumps | AI ms/tick | AI max | Server ms avg | Server max |\n");
		sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (Trip t : trips) {
			sb.append(String.format("| %d | %s | %.0f | %s | %d | %.0f | %.2f | %d | %d | %d | %d | %d | %.2f | %.1f | %.1f | %.1f |%n", t.index(), t.pair().tag(),
				t.straight(), t.outcome(), t.ticks(), t.walked(), t.straight() == 0 ? 0 : t.walked() / t.straight(), t.searches(), t.nodes(), t.searchTicks(),
				t.stuck(), t.jumps(), t.aiMsPerTick(), t.aiMsMax(), t.serverMsAvg(), t.serverMsMax()));
		}
		return sb.toString();
	}
}
