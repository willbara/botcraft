package com.mcfriends.colony;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mcfriends.MCFriends;
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
 * owner marked), never anyone else's. Saved in {@code <world>/mcfriends/colony.json}.
 */
public final class Colony {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Within this distance of the base counts as "at home" for chores and storage. */
	public static final double BASE_RADIUS = 24;
	/** Companions further than this don't come back just to fetch something. */
	public static final double FETCH_RANGE = 96;

	@Nullable
	private static Colony instance;

	/** The saved form. */
	private static final class Data {
		int[] base;
		String dim;
		List<int[]> chests = new ArrayList<>();
	}

	private final MinecraftServer server;
	private Data data = new Data();
	private Map<Item, Integer> stockCache = Map.of();
	private long stockAt = -1000;
	private boolean dirty;

	private Colony(MinecraftServer server) {
		this.server = server;
	}

	// ---- lifecycle -----------------------------------------------------------------------------

	public static void load(MinecraftServer server) {
		Colony c = new Colony(server);
		Path f = file(server);
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Data d = GSON.fromJson(r, Data.class);
				if (d != null) {
					c.data = d;
					if (c.data.chests == null) {
						c.data.chests = new ArrayList<>();
					}
				}
			} catch (IOException | JsonParseException e) {
				MCFriends.LOG.error("Could not read {}", f, e);
			}
		}
		instance = c;
	}

	public static void unload() {
		if (instance != null) {
			instance.save();
		}
		instance = null;
	}

	@Nullable
	public static Colony get() {
		return instance;
	}

	public void save() {
		if (!dirty) {
			return;
		}
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("colony.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(data, w);
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			MCFriends.LOG.error("Could not write {}", f, e);
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("mcfriends").resolve("colony.json");
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
		data.base = new int[]{pos.getX(), pos.getY(), pos.getZ()};
		data.dim = dim;
		dirty = true;
		MCFriends.LOG.info("Colony base set to {} in {}", pos.toShortString(), dim);
	}

	/** In the same dimension and within {@code radius} of the base. */
	public boolean near(ServerWorld world, BlockPos pos, double radius) {
		BlockPos b = base();
		return b != null && world == world() && b.getSquaredDistance(pos) <= radius * radius;
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

	/** The nearest base chest with room for this item (an empty slot, or a matching stack with space). */
	@Nullable
	public BlockPos chestWithRoom(ItemStack stack, BlockPos from) {
		ServerWorld w = world();
		if (w == null) {
			return null;
		}
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
			// Keep like with like: a chest already holding this item wins.
			double d = p.getSquaredDistance(from) - (same ? 10_000 : 0);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/**
	 * Where a new base chest should go: on solid ground near the base, not touching another
	 * chest (no accidental double chests), not on the home spot itself.
	 */
	@Nullable
	public BlockPos newChestSpot() {
		ServerWorld w = world();
		BlockPos b = base();
		if (w == null || b == null) {
			return null;
		}
		// First choice: a spot of its own. On cramped ground (a hilltop), next to another chest
		// is fine too: two singles just become a double chest.
		BlockPos spot = chestSpot(w, b, false);
		return spot != null ? spot : chestSpot(w, b, true);
	}

	@Nullable
	private BlockPos chestSpot(ServerWorld w, BlockPos b, boolean mayTouch) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.iterate(b.add(-8, -3, -8), b.add(8, 3, 8))) {
			if (p.getSquaredDistance(b) < 4 || !w.getBlockState(p).isReplaceable() || !w.getBlockState(p.up()).isReplaceable()
				|| !w.getFluidState(p).isEmpty() || !w.getBlockState(p.down()).isSideSolidFullSquare(w, p.down(), Direction.UP)) {
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

	public int chestCount() {
		return data.chests.size();
	}

	/** For tests: no base, no chests. */
	public void clear() {
		data = new Data();
		stockAt = -1000;
		dirty = true;
	}
}
