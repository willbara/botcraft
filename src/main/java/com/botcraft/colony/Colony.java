package com.botcraft.colony;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.botcraft.Botcraft;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
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
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The shared home base: where it is, which chests belong to it, and what's in them. Companions
 * only ever put things in or take things out of these chests (ones they placed, or ones the
 * owner marked), never anyone else's. Saved in {@code <world>/botcraft/colony.json}.
 */
public final class Colony {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Within this distance of the base counts as "at home" for chores and storage. */
	public static final double BASE_RADIUS = 24;
	/** Companions further than this don't come back just to fetch something. */
	public static final double FETCH_RANGE = 96;

	/**
	 * One colony per faction (see faction.Scope): the default faction's is colony.json, as before
	 * factions; others are colonies/&lt;id&gt;.json, loaded when first needed.
	 */
	private static final Map<String, Colony> INSTANCES = new java.util.LinkedHashMap<>();
	@Nullable
	private static MinecraftServer loadedFor;

	/** The saved form. */
	private static final class Data {
		int[] base;
		String dim;
		List<int[]> chests = new ArrayList<>();
		/** "x,y,z" -> purpose name, for chests that have one. */
		Map<String, String> purposes = new HashMap<>();
		/** Stock target key -> how many the base should keep (overrides the defaults; 0 = don't collect). */
		Map<String, Integer> wants = new java.util.LinkedHashMap<>();
		/** Dimension -> throwaway blocks companions put down (BlockPos longs, oldest first). */
		Map<String, long[]> placed = new HashMap<>();
		/** The base's areas: core, beds, farm, pen, home (see {@link Zones}). */
		List<Zone> zones = new ArrayList<>();
		/** Dimension -> ground at home that companions dug out, to fill back in (BlockPos longs). */
		Map<String, long[]> dug = new HashMap<>();
	}

	/**
	 * A rectangle of the base set aside for something (x1..x2, z1..z2 inclusive, ground at y-1).
	 * Planned ones are replaced when the base moves; ones the player set ({@code manual}) stay.
	 */
	public static final class Zone {
		public String kind;
		public int x1;
		public int z1;
		public int x2;
		public int z2;
		public int y;
		public boolean manual;

		public Zone(String kind, int x1, int z1, int x2, int z2, int y, boolean manual) {
			this.kind = kind;
			this.x1 = Math.min(x1, x2);
			this.z1 = Math.min(z1, z2);
			this.x2 = Math.max(x1, x2);
			this.z2 = Math.max(z1, z2);
			this.y = y;
			this.manual = manual;
		}

		public boolean contains(int x, int z) {
			return x >= x1 && x <= x2 && z >= z1 && z <= z2;
		}

		public boolean contains(BlockPos p) {
			return contains(p.getX(), p.getZ());
		}

		public BlockPos center() {
			return new BlockPos((x1 + x2) / 2, y, (z1 + z2) / 2);
		}

		public boolean overlaps(Zone o, int gap) {
			return x1 - gap <= o.x2 && o.x1 - gap <= x2 && z1 - gap <= o.z2 && o.z1 - gap <= z2;
		}

		@Override
		public String toString() {
			return kind + " " + x1 + "," + z1 + " to " + x2 + "," + z2 + " (" + (x2 - x1 + 1) + "x" + (z2 - z1 + 1) + (manual ? ", set by hand" : "") + ")";
		}
	}

	/** Most remembered throwaway blocks per dimension; the oldest are forgotten first. */
	private static final int MAX_PLACED = 8192;
	/** {@link Data#placed} while running, in insertion order. */
	private final Map<String, java.util.LinkedHashSet<Long>> placed = new HashMap<>();
	/** {@link Data#dug} while running. */
	private final Map<String, java.util.LinkedHashSet<Long>> dug = new HashMap<>();

	private final MinecraftServer server;
	/** The faction this is the base of. */
	private final String id;
	private Data data = new Data();
	private Map<Item, Integer> stockCache = Map.of();
	private long stockAt = -1000;
	private boolean dirty;

	private Colony(MinecraftServer server, String id) {
		this.server = server;
		this.id = id;
	}

	/** The faction this colony belongs to. */
	public String id() {
		return id;
	}

	// ---- lifecycle -----------------------------------------------------------------------------

	public static void load(MinecraftServer server) {
		INSTANCES.clear();
		loadedFor = server;
		INSTANCES.put(com.botcraft.faction.Factions.DEFAULT, read(server, com.botcraft.faction.Factions.DEFAULT));
	}

	private static Colony read(MinecraftServer server, String id) {
		Colony c = new Colony(server, id);
		Path f = file(server, id);
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Data d = GSON.fromJson(r, Data.class);
				if (d != null) {
					c.data = d;
					if (c.data.chests == null) {
						c.data.chests = new ArrayList<>();
					}
					if (c.data.purposes == null) {
						c.data.purposes = new HashMap<>();
					}
					if (c.data.wants == null) {
						c.data.wants = new java.util.LinkedHashMap<>();
					}
					if (c.data.zones == null) {
						c.data.zones = new ArrayList<>();
					}
					readLedger(c.data.placed, c.placed);
					readLedger(c.data.dug, c.dug);
				}
			} catch (IOException | JsonParseException e) {
				Botcraft.LOG.error("Could not read {}", f, e);
			}
		}
		return c;
	}

	private static void readLedger(@Nullable Map<String, long[]> saved, Map<String, java.util.LinkedHashSet<Long>> into) {
		if (saved != null) {
			saved.forEach((dim, longs) -> {
				var set = new java.util.LinkedHashSet<Long>();
				for (long l : longs) {
					set.add(l);
				}
				into.put(dim, set);
			});
		}
	}

	public static void unload() {
		for (Colony c : INSTANCES.values()) {
			c.save();
		}
		INSTANCES.clear();
		loadedFor = null;
	}

	/** The colony of the faction in scope (see faction.Scope); null before the server has started. */
	@Nullable
	public static Colony get() {
		return of(com.botcraft.faction.Scope.current());
	}

	/** A faction's colony (loaded or started when first asked for). */
	@Nullable
	public static Colony of(String faction) {
		if (loadedFor == null) {
			return null;
		}
		String key = faction == null ? com.botcraft.faction.Factions.DEFAULT : faction;
		Colony c = INSTANCES.get(key);
		if (c == null) {
			c = read(loadedFor, key);
			INSTANCES.put(key, c);
		}
		return c;
	}

	/** Every colony loaded so far. */
	public static java.util.Collection<Colony> all() {
		return java.util.List.copyOf(INSTANCES.values());
	}

	/** Forget a faction's colony (the faction was removed); its file stays. */
	public static void drop(String faction) {
		Colony c = INSTANCES.remove(faction);
		if (c != null) {
			c.save();
		}
	}

	public void save() {
		if (!dirty) {
			return;
		}
		Path f = file(server, id);
		data.placed = new HashMap<>();
		placed.forEach((dim, set) -> data.placed.put(dim, set.stream().mapToLong(Long::longValue).toArray()));
		data.dug = new HashMap<>();
		dug.forEach((dim, set) -> data.dug.put(dim, set.stream().mapToLong(Long::longValue).toArray()));
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(data, w);
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			Botcraft.LOG.error("Could not write {}", f, e);
		}
	}

	private static Path file(MinecraftServer server, String id) {
		Path dir = server.getSavePath(WorldSavePath.ROOT).resolve("botcraft");
		return com.botcraft.faction.Factions.DEFAULT.equals(id) ? dir.resolve("colony.json") : dir.resolve("colonies").resolve(id + ".json");
	}

	// ---- the base -------------------------------------------------------------------------------

	public boolean hasBase() {
		return data.base != null && world() != null;
	}

	@Nullable
	public BlockPos base() {
		return data.base == null ? null : new BlockPos(data.base[0], data.base[1], data.base[2]);
	}

	@Nullable
	public ServerWorld world() {
		if (data.dim == null) {
			return null;
		}
		return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, new Identifier(data.dim)));
	}

	public void setBase(BlockPos pos, String dim) {
		BlockPos old = base();
		data.base = new int[]{pos.getX(), pos.getY(), pos.getZ()};
		data.dim = dim;
		if (old == null || !old.equals(pos)) {
			data.zones.removeIf(z -> !z.manual); // planned for the old spot: plan again
		}
		dirty = true;
		Botcraft.LOG.info("Colony base set to {} in {}", pos.toShortString(), dim);
	}

	/** In the same dimension and within {@code radius} of the base. */
	public boolean near(ServerWorld world, BlockPos pos, double radius) {
		BlockPos b = base();
		return b != null && world == world() && b.getSquaredDistance(pos) <= radius * radius;
	}

	/** Before the home zone is planned, home is this far around the base spot. */
	public static final int PROTECTED_RADIUS = 12;
	/** Mining leaves home alone from this far under its ground upwards. */
	private static final int PROTECTED_DEPTH = 6;

	/**
	 * Part of home's ground (anything in the home zone from a few blocks under the ground up,
	 * hills included): mining and digging for dirt leave it alone. (Soaks showed mining jobs
	 * digging the stone right under the base until its chests and furnaces stood on one-block
	 * pillars high above the new ground.) Deep caves under home can still be mined.
	 */
	public boolean protectsGround(ServerWorld w, BlockPos pos) {
		BlockPos b = base();
		if (b == null || w != world() || !com.botcraft.util.Experiments.on("spareBase") || !Zones.inHome(this, w, pos)) {
			return false;
		}
		BaseMap map = BaseMap.of(id);
		int ground = b.getY() - 1;
		if (map != null && map.ground(pos.getX(), pos.getZ()) != BaseMap.Ground.UNKNOWN) {
			ground = Math.min(ground, map.groundY(pos.getX(), pos.getZ()));
		}
		return pos.getY() >= ground - PROTECTED_DEPTH;
	}

	// ---- chests ---------------------------------------------------------------------------------

	/** Base chests that still exist (forgetting ones that were broken). */
	public List<BlockPos> chests() {
		ServerWorld w = world();
		List<BlockPos> out = new ArrayList<>();
		if (w == null) {
			return out;
		}
		boolean changed = data.chests.removeIf(a -> {
			BlockPos p = new BlockPos(a[0], a[1], a[2]);
			return w.isChunkLoaded(p) && inventory(w, p) == null;
		});
		if (changed) {
			dirty = true;
		}
		for (int[] a : data.chests) {
			out.add(new BlockPos(a[0], a[1], a[2]));
		}
		return out;
	}

	public boolean isBaseChest(BlockPos pos) {
		for (int[] a : data.chests) {
			if (a[0] == pos.getX() && a[1] == pos.getY() && a[2] == pos.getZ()) {
				return true;
			}
		}
		return false;
	}

	public void addChest(BlockPos pos) {
		if (!isBaseChest(pos)) {
			data.chests.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
			dirty = true;
			stockAt = -1000;
		}
		// The other half of a double chest belongs too.
		ServerWorld w = world();
		if (w != null) {
			BlockState state = w.getBlockState(pos);
			if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE) {
				BlockPos other = pos.offset(ChestBlock.getFacing(state));
				if (!isBaseChest(other) && w.getBlockState(other).getBlock() instanceof ChestBlock) {
					data.chests.add(new int[]{other.getX(), other.getY(), other.getZ()});
				}
			}
		}
	}

	/** The container at a position (a chest, double chest or barrel), or null. */
	@Nullable
	public static Inventory inventory(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getInventory(chest, state, world, pos, true);
		}
		if (state.getBlock() instanceof BarrelBlock && world.getBlockEntity(pos) instanceof Inventory inv) {
			return inv;
		}
		return null;
	}

	// ---- stock ----------------------------------------------------------------------------------

	/** Everything in the base chests, by item (refreshed at most once a second). */
	public Map<Item, Integer> stock() {
		ServerWorld w = world();
		if (w == null) {
			return Map.of();
		}
		if (server.getTicks() - stockAt < 20) {
			return stockCache;
		}
		Map<Item, Integer> counts = new HashMap<>();
		for (BlockPos p : chests()) {
			// Each half of a double chest is registered on its own: count only this half's slots.
			if (!w.isChunkLoaded(p) || !(w.getBlockEntity(p) instanceof Inventory inv)) {
				continue;
			}
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (!s.isEmpty()) {
					counts.merge(s.getItem(), s.getCount(), Integer::sum);
				}
			}
		}
		stockCache = counts;
		stockAt = server.getTicks();
		return counts;
	}

	/** Forget the cached stock (after putting things in or taking them out). */
	public void touched() {
		stockAt = -1000;
	}

	public int count(Predicate<ItemStack> which) {
		int n = 0;
		for (Map.Entry<Item, Integer> e : stock().entrySet()) {
			if (which.test(new ItemStack(e.getKey()))) {
				n += e.getValue();
			}
		}
		return n;
	}

	/** The nearest base chest holding something that matches. */
	@Nullable
	public BlockPos chestWith(Predicate<ItemStack> which, BlockPos from) {
		ServerWorld w = world();
		if (w == null) {
			return null;
		}
		return chests().stream().filter(p -> {
			Inventory inv = w.isChunkLoaded(p) ? inventory(w, p) : null;
			if (inv == null) {
				return false;
			}
			for (int i = 0; i < inv.size(); i++) {
				if (!inv.getStack(i).isEmpty() && which.test(inv.getStack(i))) {
					return true;
				}
			}
			return false;
		}).min(Comparator.comparingDouble(p -> p.getSquaredDistance(from))).orElse(null);
	}

	/**
	 * Where to put this item: a chest for its kind first, then a general chest (no purpose), and
	 * only then any chest with room. Within each, a chest already holding the item wins.
	 */
	@Nullable
	public BlockPos chestWithRoom(ItemStack stack, BlockPos from) {
		ServerWorld w = world();
		if (w == null) {
			return null;
		}
		Purpose kind = Purpose.of(stack);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : chests()) {
			Inventory inv = w.isChunkLoaded(p) ? inventory(w, p) : null;
			if (inv == null) {
				continue;
			}
			boolean same = false;
			boolean room = false;
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (s.isEmpty()) {
					room = true;
				} else if (ItemStack.canCombine(s, stack)) {
					same = true;
					if (s.getCount() < s.getMaxCount()) {
						room = true;
					}
				}
			}
			if (!room) {
				continue;
			}
			Purpose purpose = purpose(p);
			double rank = purpose == kind ? 0 : purpose == null ? 1 : 2; // ours, general, someone else's
			double d = rank * 1_000_000 + p.getSquaredDistance(from) - (same ? 10_000 : 0);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** What this chest is for, or null for a general chest. Both halves of a double chest share it. */
	@Nullable
	public Purpose purpose(BlockPos pos) {
		String name = data.purposes.get(key(pos));
		if (name == null) {
			ServerWorld w = world();
			if (w != null && w.getBlockState(pos).getBlock() instanceof ChestBlock
				&& w.getBlockState(pos).get(ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE) {
				name = data.purposes.get(key(pos.offset(ChestBlock.getFacing(w.getBlockState(pos)))));
			}
		}
		try {
			return name == null ? null : Purpose.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public void setPurpose(BlockPos pos, @Nullable Purpose purpose) {
		if (purpose == null) {
			data.purposes.remove(key(pos));
		} else {
			data.purposes.put(key(pos), purpose.name());
		}
		dirty = true;
	}

	private static String key(BlockPos p) {
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}

	/** Each chest's purpose and how many items it holds (for /companion colony). */
	public List<String> describeChests() {
		ServerWorld w = world();
		List<String> out = new ArrayList<>();
		if (w == null) {
			return out;
		}
		for (BlockPos p : chests()) {
			int items = 0;
			if (w.isChunkLoaded(p) && w.getBlockEntity(p) instanceof Inventory inv) {
				for (int i = 0; i < inv.size(); i++) {
					items += inv.getStack(i).getCount();
				}
			}
			Purpose purpose = purpose(p);
			out.add(p.toShortString() + ": " + (purpose == null ? "general" : purpose.label) + ", " + items + " item(s)");
		}
		return out;
	}

	/**
	 * Where a new base chest should go: on solid ground near the base, not touching another
	 * chest (no accidental double chests), not on the home spot itself.
	 */
	@Nullable
	public BlockPos newChestSpot() {
		return newChestSpot(java.util.Set.of());
	}

	/** Like {@link #newChestSpot()}, skipping spots that didn't work. */
	@Nullable
	public BlockPos newChestSpot(java.util.Set<BlockPos> exclude) {
		ServerWorld w = world();
		BlockPos b = base();
		if (w == null || b == null) {
			return null;
		}
		this.exclude = exclude;
		// In the core first (the base's planned middle), so chests end up together.
		Zone core = com.botcraft.util.Experiments.on("zonePlacement") ? zone(Zones.CORE) : null;
		if (core != null) {
			coreOnly = core;
			BlockPos inCore = chestSpot(w, b, false, 8, 3);
			inCore = inCore != null ? inCore : chestSpot(w, b, true, 8, 3);
			coreOnly = null;
			if (inCore != null) {
				return inCore;
			}
		}
		// First choice: a spot of its own. On cramped ground (a hilltop), next to another chest
		// is fine too: two singles just become a double chest.
		BlockPos spot = chestSpot(w, b, false, 8, 3);
		spot = spot != null ? spot : chestSpot(w, b, true, 8, 3);
		// A steep base (a mountain top): look further up and down the slope.
		spot = spot != null ? spot : chestSpot(w, b, false, 12, 8);
		return spot != null ? spot : chestSpot(w, b, true, 12, 8);
	}

	private java.util.Set<BlockPos> exclude = java.util.Set.of();
	/** While looking in the core only. */
	@Nullable
	private Zone coreOnly;

	@Nullable
	private BlockPos chestSpot(ServerWorld w, BlockPos b, boolean mayTouch, int across, int updown) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.iterate(b.add(-across, -updown, -across), b.add(across, updown, across))) {
			if (p.getSquaredDistance(b) < 4 || !w.getBlockState(p).isReplaceable() || !w.getBlockState(p.up()).isReplaceable()
				|| !w.getFluidState(p).isEmpty() || !w.getBlockState(p.down()).isSideSolidFullSquare(w, p.down(), Direction.UP)
				|| exclude.contains(p) || (coreOnly != null && !coreOnly.contains(p)) || (coreOnly == null && Zones.reserved(this, w, p))) {
				continue;
			}
			// Someone standing there (companions gather at the base) would block the chest.
			if (!w.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new net.minecraft.util.math.Box(p), e -> e.isAlive()).isEmpty()) {
				continue;
			}
			boolean touches = false;
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos n = p.offset(d);
				BlockState ns = w.getBlockState(n);
				if (inventory(w, n) != null) {
					// A chest that's already double can't take a third half; never put one beside it.
					if (!mayTouch || (ns.getBlock() instanceof ChestBlock && ns.get(ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE)) {
						touches = true;
					}
				}
			}
			double d = p.getSquaredDistance(b) + Math.abs(p.getY() - b.getY()) * 6;
			if (!touches && d < bestD) {
				bestD = d;
				best = p.toImmutable();
			}
		}
		return best;
	}

	// ---- zones ------------------------------------------------------------------------------------

	public List<Zone> zones() {
		return java.util.Collections.unmodifiableList(data.zones);
	}

	@Nullable
	public Zone zone(String kind) {
		for (Zone z : data.zones) {
			if (z.kind.equals(kind)) {
				return z;
			}
		}
		return null;
	}

	/** Set (or replace) the zone of this kind. */
	public void setZone(Zone zone) {
		data.zones.removeIf(z -> z.kind.equals(zone.kind));
		data.zones.add(zone);
		dirty = true;
	}

	/** Forget a zone (a planned one is planned again; a hand-set one goes back to being planned). */
	public void clearZone(String kind) {
		if (data.zones.removeIf(z -> z.kind.equals(kind))) {
			dirty = true;
		}
	}

	// ---- blocks companions put down -------------------------------------------------------------

	/**
	 * Remember a throwaway block a companion put down to get somewhere (a bridge, a pillar, a
	 * shelter roof). Vanilla doesn't record who placed what, and navigation never breaks built
	 * blocks; these it may, so nobody gets walled in by a roof another companion left behind.
	 */
	public void recordPlaced(String dim, BlockPos pos) {
		var set = placed.computeIfAbsent(dim, d -> new java.util.LinkedHashSet<>());
		set.remove(pos.asLong()); // re-adding moves it to the newest end
		set.add(pos.asLong());
		if (set.size() > MAX_PLACED) {
			var it = set.iterator();
			it.next();
			it.remove();
		}
		dirty = true;
	}

	/** A companion put a throwaway block down here (it may since have been replaced: check the block too). */
	public boolean placedByCompanion(String dim, BlockPos pos) {
		var set = placed.get(dim);
		return set != null && set.contains(pos.asLong());
	}

	public void forgetPlaced(String dim, BlockPos pos) {
		var set = placed.get(dim);
		if (set != null && set.remove(pos.asLong())) {
			dirty = true;
		}
	}

	/** Every throwaway block companions put down in a dimension (may since be gone: check). */
	public List<BlockPos> placedIn(String dim) {
		var set = placed.get(dim);
		return set == null ? List.of() : set.stream().map(BlockPos::fromLong).toList();
	}

	// ---- ground companions dug out at home ------------------------------------------------------------

	/** A companion dug out the ground here at home: fill it back in (see {@link Upkeep}). */
	public void recordDug(String dim, BlockPos pos) {
		var set = dug.computeIfAbsent(dim, d -> new java.util.LinkedHashSet<>());
		if (set.add(pos.asLong())) {
			if (set.size() > MAX_PLACED) {
				var it = set.iterator();
				it.next();
				it.remove();
			}
			dirty = true;
		}
	}

	public void forgetDug(String dim, BlockPos pos) {
		var set = dug.get(dim);
		if (set != null && set.remove(pos.asLong())) {
			dirty = true;
		}
	}

	public List<BlockPos> dugIn(String dim) {
		var set = dug.get(dim);
		return set == null ? List.of() : set.stream().map(BlockPos::fromLong).toList();
	}

	// ---- stock targets ---------------------------------------------------------------------------

	/** How many of a stock target the base should keep: the player's setting, else the default. */
	public int want(String key, int fallback) {
		Integer w = data.wants.get(key);
		return w == null ? fallback : w;
	}

	/** Keep {@code n} of something at the base (0: stop collecting it). */
	public void setWant(String key, int n) {
		data.wants.put(key, Math.max(0, n));
		dirty = true;
	}

	/** What the player has asked the base to keep (target key -> amount). */
	public Map<String, Integer> wants() {
		return java.util.Collections.unmodifiableMap(data.wants);
	}

	public int chestCount() {
		return data.chests.size();
	}

	/** For tests: no base, no chests. */
	public void clear() {
		data = new Data();
		placed.clear();
		dug.clear();
		stockAt = -1000;
		dirty = true;
	}
}
