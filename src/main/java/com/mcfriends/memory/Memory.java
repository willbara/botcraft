package com.mcfriends.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mcfriends.MCFriends;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a companion remembers, persisted per companion at {@code <world>/mcfriends/memory/<name>.json}:
 * <ul>
 *   <li><b>places</b>: named spots (home, bed, last death, crafting table);</li>
 *   <li><b>sightings</b>: interesting blocks seen while scanning (ores, trees), so the next mining
 *       trip can go straight there;</li>
 *   <li><b>stats</b>: lifetime counters (logs chopped, ores mined, kills, deaths, crafts);</li>
 *   <li><b>people</b>: players met, when last seen, how they treated us.</li>
 * </ul>
 * Plain data + small helpers; the AI decides what to record. Server thread only.
 */
public class Memory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_SIGHTINGS = 96;

	public static class Place {
		public int x;
		public int y;
		public int z;
		public String dim;
		public long time;

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	public static class Sighting {
		public String kind;
		public int x;
		public int y;
		public int z;
		public String dim;
		public long time;

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	public static class Person {
		public String name;
		public long firstMet;
		public long lastSeen;
		public int orders;
		public int thanks;
	}

	public Map<String, Place> places = new LinkedHashMap<>();
	public List<Sighting> sightings = new ArrayList<>();
	public Map<String, Integer> stats = new LinkedHashMap<>();
	public Map<String, Person> people = new HashMap<>();

	private transient boolean dirty;

	// ---- places ---------------------------------------------------------------------------------

	public void setPlace(String name, BlockPos pos, String dim, long time) {
		Place p = new Place();
		p.x = pos.getX();
		p.y = pos.getY();
		p.z = pos.getZ();
		p.dim = dim;
		p.time = time;
		places.put(name, p);
		dirty = true;
	}

	@Nullable
	public Place place(String name) {
		return places.get(name);
	}

	public void forgetPlace(String name) {
		if (places.remove(name) != null) {
			dirty = true;
		}
	}

	// ---- sightings ------------------------------------------------------------------------------

	/** Remember a block of interest. Nearby sightings of the same kind are merged. */
	public void sight(String kind, BlockPos pos, String dim, long time) {
		for (Sighting s : sightings) {
			if (s.kind.equals(kind) && s.dim.equals(dim) && s.pos().getSquaredDistance(pos) < 6 * 6) {
				s.time = time;
				return;
			}
		}
		Sighting s = new Sighting();
		s.kind = kind;
		s.x = pos.getX();
		s.y = pos.getY();
		s.z = pos.getZ();
		s.dim = dim;
		s.time = time;
		sightings.add(s);
		if (sightings.size() > MAX_SIGHTINGS) {
			sightings.sort(Comparator.comparingLong(a -> a.time));
			sightings.remove(0);
		}
		dirty = true;
	}

	public void forgetSighting(String kind, BlockPos pos) {
		if (sightings.removeIf(s -> s.kind.equals(kind) && s.pos().getSquaredDistance(pos) < 6 * 6)) {
			dirty = true;
		}
	}

	/** Sightings of a kind within range, nearest first. */
	public List<Sighting> sightings(String kind, BlockPos near, String dim, double range) {
		List<Sighting> out = new ArrayList<>();
		for (Sighting s : sightings) {
			if (s.kind.equals(kind) && s.dim.equals(dim) && s.pos().getSquaredDistance(near) <= range * range) {
				out.add(s);
			}
		}
		out.sort(Comparator.comparingDouble(s -> s.pos().getSquaredDistance(near)));
		return out;
	}

	// ---- stats ----------------------------------------------------------------------------------

	public void count(String stat, int amount) {
		if (amount != 0) {
			stats.merge(stat, amount, Integer::sum);
			dirty = true;
		}
	}

	public int stat(String stat) {
		return stats.getOrDefault(stat, 0);
	}

	// ---- people ---------------------------------------------------------------------------------

	public Person person(String name, long time) {
		Person p = people.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> {
			Person n = new Person();
			n.name = name;
			n.firstMet = time;
			return n;
		});
		p.lastSeen = time;
		dirty = true;
		return p;
	}

	@Nullable
	public Person knownPerson(String name) {
		return people.get(name.toLowerCase(Locale.ROOT));
	}

	// ---- persistence ----------------------------------------------------------------------------

	private static Path file(MinecraftServer server, String companion) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("mcfriends").resolve("memory")
			.resolve(companion.toLowerCase(Locale.ROOT) + ".json");
	}

	public static Memory load(MinecraftServer server, String companion) {
		Path f = file(server, companion);
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Memory m = GSON.fromJson(r, Memory.class);
				if (m != null) {
					m.places = m.places == null ? new LinkedHashMap<>() : m.places;
					m.sightings = m.sightings == null ? new ArrayList<>() : m.sightings;
					m.stats = m.stats == null ? new LinkedHashMap<>() : m.stats;
					m.people = m.people == null ? new HashMap<>() : m.people;
					return m;
				}
			} catch (IOException | JsonParseException e) {
				MCFriends.LOG.error("Could not read memory {}", f, e);
			}
		}
		return new Memory();
	}

	public void saveIfDirty(MinecraftServer server, String companion) {
		if (!dirty) {
			return;
		}
		Path f = file(server, companion);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			MCFriends.LOG.error("Could not write memory {}", f, e);
		}
	}

	public static void delete(MinecraftServer server, String companion) {
		try {
			Files.deleteIfExists(file(server, companion));
		} catch (IOException e) {
			MCFriends.LOG.warn("Could not delete memory for {}", companion, e);
		}
	}
}
