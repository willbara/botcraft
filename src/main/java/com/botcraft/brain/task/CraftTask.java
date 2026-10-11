package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.CraftPlanner;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.nav.Terrain;
import com.botcraft.util.AiLog;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Craft {@code count} of an item, working out intermediate crafts (logs -> planks -> sticks)
 * with {@link CraftPlanner}. Missing raw materials become prerequisite jobs (chop logs, mine
 * cobblestone/coal...) queued in front of this one. 3x3 recipes need a crafting table: we use
 * one within reach, walk to one we remember, or place our own and pick it back up afterwards.
 *
 * <p>Crafting itself moves items directly in the inventory (there is no client to click a
 * crafting screen), at a human-ish pace with the arm swinging, and runs the vanilla
 * on-craft hook so statistics and advancements still count.
 */
public class CraftTask extends Task {
	private enum Phase { PLAN, TABLE, CRAFT, PICKUP }

	private static final int TICKS_PER_CRAFT = 8;

	private final Item item;
	private final int count;
	private Phase phase = Phase.PLAN;
	private int phaseTicks;
	private CraftPlanner.Plan plan;
	private int stepIndex;
	@Nullable
	private BlockPos table;
	private boolean placedTable;
	/** Tables we couldn't get to (behind a corner, across a gap): don't keep trying them. */
	private final java.util.Set<BlockPos> badTables = new java.util.HashSet<>();
	private int relocations;
	private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
	private int spotFails;
	private final java.util.Random random = new java.util.Random();
	private boolean tableBroken;
	private int made;

	public CraftTask(Companion c, Item item, int count) {
		super(c);
		this.item = item;
		this.count = Math.max(1, count);
	}

	@Override
	public String name() {
		return "craft_" + ItemNames.id(item);
	}

	@Override
	public String describe() {
		return "crafting " + (count > 1 ? count + " " : "") + ItemNames.display(item);
	}

	public Item item() {
		return item;
	}

	public int made() {
		return made;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 120;
	}

	@Override
	protected void onStart(boolean resumed) {
		setPhase(Phase.PLAN);
	}

	private void setPhase(Phase p) {
		phase = p;
		phaseTicks = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		phaseTicks++;
		return switch (phase) {
			case PLAN -> plan(p);
			case TABLE -> table(p);
			case CRAFT -> craft(p);
			case PICKUP -> pickup(p);
		};
	}

	private Status plan(CompanionPlayer p) {
		if (!CraftPlanner.craftable(c.server, item) && com.botcraft.knowledge.Smelting.smeltable(c.server, item)) {
			return deferTo(List.of("smelt " + ItemNames.id(item) + " " + count)); // e.g. "make iron ingots"
		}
		// Already made and sitting in the base chests (a spare sword, someone's old pickaxe)? Take it.
		int carried = Inventories.count(p.getInventory(), st -> st.isOf(item));
		// Only on the first try: if the chests turned out not to have it after all (taken, out of
		// reach), the next try crafts it instead of going back to the chests again.
		boolean firstTry = spec() == null || com.botcraft.brain.Jobs.attempt(spec()) == 0;
		if (made == 0 && carried < count && firstTry) {
			String stored = com.botcraft.brain.Jobs.withdrawSpec(c, ItemNames.id(item), st -> st.isOf(item), count - carried);
			if (stored != null) {
				return deferTo(List.of(stored));
			}
		}
		if (made == 0 && carried >= count && spec() != null && com.botcraft.brain.Jobs.attempt(spec()) > 0) {
			return quietSuccess(); // fetched it from the chests: done
		}
		plan = CraftPlanner.plan(c.server, inventoryCounts(p.getInventory()), item, count);
		if (plan.needsTable() && plan.problem() == null && !hasTableItem(p) && findTable(p) == null) {
			// We'll have to make a table first, out of the same planks: count it in now, or the
			// table eats the materials and we go round in circles fetching one more log.
			plan = CraftPlanner.plan(c.server, inventoryCounts(p.getInventory()), item, count, true);
		}
		AiLog.log(c.name(), "craft {} x{}: {} crafts, missing {}, problem {}", ItemNames.id(item), count, plan.crafts().size(), plan.missing(), plan.problem());
		if (!plan.missing().isEmpty() && plan.problem() == null) {
			List<String> fetch = fromStock(p);
			if (!fetch.isEmpty()) {
				return deferTo(fetch);
			}
		}
		if (plan.problem() != null) {
			return fail(plan.problem());
		}
		if (!plan.missing().isEmpty()) {
			// First see if a friend nearby can spare the first thing we're missing.
			if (!askedFriend && !com.botcraft.brain.Jobs.optional(spec())) {
				askedFriend = true;
				Map.Entry<Item, Integer> first = plan.missing().entrySet().iterator().next();
				String key = shareKey(first.getKey());
				String label = key.equals("logs") ? "logs" : ItemNames.plural(ItemNames.display(first.getKey()), first.getValue());
				com.botcraft.Companion helper = com.botcraft.brain.Sharing.ask(c, key, label, first.getValue(), false);
				if (helper != null) {
					return deferTo(List.of("await " + key + " " + first.getValue() + " " + helper.name()));
				}
			}
			List<String> prereqs = new ArrayList<>();
			int logs = 0;
			for (Map.Entry<Item, Integer> e : plan.missing().entrySet()) {
				if (new ItemStack(e.getKey()).isIn(ItemTags.LOGS)) {
					logs += e.getValue();
					continue;
				}
				String spec = com.botcraft.brain.Jobs.sourceSpec(c, e.getKey(), e.getValue());
				if (spec == null) {
					return fail("I don't know how to get " + ItemNames.display(e.getKey()));
				}
				prereqs.add(spec);
			}
			if (logs > 0) {
				String stored = com.botcraft.brain.Jobs.withdrawSpec(c, "logs", Inventories::isLog, logs);
				prereqs.add(0, stored != null ? stored : "gather logs " + logs);
			}
			return deferTo(prereqs);
		}
		stepIndex = 0;
		if (plan.needsTable()) {
			table = findTable(p);
			if (table == null && !hasTableItem(p)) {
				// Make a table first (4 planks), then come back to this.
				return deferTo(List.of("craft crafting_table 1"));
			}
			setPhase(Phase.TABLE);
		} else {
			setPhase(Phase.CRAFT);
		}
		return Status.RUNNING;
	}

	private Status table(CompanionPlayer p) {
		if (table != null && p.getWorld().getBlockState(table).isOf(Blocks.CRAFTING_TABLE)) {
			if (c.actions().canReachBlock(table)) {
				c.nav().stop();
				setPhase(Phase.CRAFT);
				return Status.RUNNING;
			}
			if (phaseTicks == 1 || c.nav().state() == Navigator.State.IDLE) {
				// Line of sight, not just distance: a corner block can hide a table that's "close".
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), table, 3.5), false);
			}
			if (c.nav().state() == Navigator.State.FAILED || phaseTicks > 20 * 30) {
				AiLog.log(c.name(), "can't get to the crafting table at {}, not using it", table.toShortString());
				badTables.add(table);
				table = null; // can't get there: place our own
				phaseTicks = 0;
			}
			return Status.RUNNING;
		}
		table = null;
		if (!hasTableItem(p)) {
			// The table we meant to put down isn't on us any more (dropped, used up): plan again.
			AiLog.log(c.name(), "no crafting table to put down, re-planning");
			setPhase(Phase.PLAN);
			return Status.RUNNING;
		}
		BlockPos spot = Spots.placeSpot(c, badSpots);
		if (spot == null) {
			// Cramped (on a pillar, in leaves...): step out somewhere more open and try again.
			if (phaseTicks >= 20 && !c.nav().isBusy() && relocations++ < 3) {
				c.nav().moveTo(com.botcraft.nav.Goal.near(Spots.roomySpot(c, random), 1.5), false);
			}
			return phaseTicks > 20 * 40 ? fail("no room to put down a crafting table") : Status.RUNNING;
		}
		if (c.actions().placeBlock(spot, s -> s.isOf(Items.CRAFTING_TABLE))) {
			table = spot;
			placedTable = true;
			AiLog.log(c.name(), "placed a crafting table at {}", spot.toShortString());
		} else if (++spotFails % 10 == 0) {
			AiLog.log(c.name(), "can't place a crafting table at {} ({}), trying elsewhere", spot.toShortString(), c.actions().lastPlaceProblem());
			badSpots.add(spot);
			if (badSpots.size() > 6) {
				if (relocations++ >= 3) {
					return fail("couldn't place a crafting table");
				}
				// Nothing works around here: try again from somewhere else.
				badSpots.clear();
				c.nav().moveTo(com.botcraft.nav.Goal.near(Spots.roomySpot(c, random), 1.5), false);
			}
		}
		return Status.RUNNING;
	}

	private Status craft(CompanionPlayer p) {
		if (table != null) {
			c.actions().lookAt(Vec3d.ofCenter(table));
		}
		if (stepIndex >= plan.crafts().size()) {
			if (placedTable && table != null && !atBase(table)) {
				setPhase(Phase.PICKUP);
				return Status.RUNNING;
			}
			return Status.SUCCESS;
		}
		if (phaseTicks % TICKS_PER_CRAFT != 0) {
			p.swingHand(Hand.MAIN_HAND);
			return Status.RUNNING;
		}
		CraftPlanner.Craft step = plan.crafts().get(stepIndex);
		if (!step.recipe().fits(2, 2) && (table == null || !c.actions().canReachBlock(table))) {
			setPhase(Phase.TABLE);
			return Status.RUNNING;
		}
		if (!craftOnce(p, step)) {
			// Inventory changed under us (ate, dropped...): re-plan.
			AiLog.log(c.name(), "craft step {} no longer possible, re-planning", ItemNames.id(step.output()));
			setPhase(Phase.PLAN);
			return Status.RUNNING;
		}
		c.perception().invalidateInventory();
		return Status.RUNNING;
	}

	/** One craft of the step; advances the step when all its repetitions are done. */
	private int doneOfStep;

	private boolean craftOnce(CompanionPlayer p, CraftPlanner.Craft step) {
		PlayerInventory inv = p.getInventory();
		List<Ingredient> ings = step.recipe().getIngredients();
		// Check first, then take, so a failed check leaves the inventory untouched.
		Map<Integer, Integer> take = new HashMap<>();
		for (Ingredient ing : ings) {
			if (ing.isEmpty()) {
				continue;
			}
			int slot = -1;
			for (int i = 0; i < inv.main.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (!s.isEmpty() && ing.test(s) && s.getCount() > take.getOrDefault(i, 0)) {
					slot = i;
					break;
				}
			}
			if (slot < 0) {
				return false;
			}
			take.merge(slot, 1, Integer::sum);
		}
		List<ItemStack> remainders = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : take.entrySet()) {
			ItemStack s = inv.getStack(e.getKey());
			Item rem = s.getItem().getRecipeRemainder();
			if (rem != null) {
				remainders.add(new ItemStack(rem, e.getValue()));
			}
			s.decrement(e.getValue());
		}
		ItemStack out = step.recipe().getOutput(c.server.getRegistryManager()).copy();
		out.onCraft(p.getWorld(), p, out.getCount());
		give(p, out);
		for (ItemStack r : remainders) {
			give(p, r);
		}
		p.swingHand(Hand.MAIN_HAND);
		if (out.isOf(item) || step.output() == item) {
			made += step.recipe().getOutput(c.server.getRegistryManager()).getCount();
		}
		c.memory().count("crafted_" + ItemNames.id(step.output()), step.recipe().getOutput(c.server.getRegistryManager()).getCount());
		if (++doneOfStep >= step.times()) {
			doneOfStep = 0;
			stepIndex++;
		}
		return true;
	}

	private static void give(CompanionPlayer p, ItemStack stack) {
		if (!p.getInventory().insertStack(stack) && !stack.isEmpty()) {
			p.dropItem(stack, false);
		}
	}

	/**
	 * What we'd take from the base chests to make this: plan with what we carry plus what's
	 * stored, and fetch the difference (sticks in the chest beat chopping a tree for them).
	 */
	private List<String> fromStock(CompanionPlayer p) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		List<String> out = new ArrayList<>();
		if (!com.botcraft.brain.Jobs.canFetch(c)) {
			return out;
		}
		Map<Item, Integer> carried = inventoryCounts(p.getInventory());
		Map<Item, Integer> stock = colony.stock();
		if (stock.isEmpty()) {
			return out;
		}
		Map<Item, Integer> both = new java.util.HashMap<>(carried);
		stock.forEach((it, n) -> both.merge(it, n, Integer::sum));
		boolean withTable = plan.needsTable() && !hasTableItem(p) && findTable(p) == null;
		CraftPlanner.Plan withStock = CraftPlanner.plan(c.server, both, item, count, withTable);
		if (withStock.problem() != null || withStock.missing().size() >= plan.missing().size() && !withStock.ready()) {
			return out; // the chests don't help
		}
		for (Map.Entry<Item, Integer> e : withStock.used().entrySet()) {
			int take = Math.min(e.getValue() - carried.getOrDefault(e.getKey(), 0), stock.getOrDefault(e.getKey(), 0));
			if (take > 0) {
				out.add("withdraw " + ItemNames.id(e.getKey()) + " " + take);
			}
		}
		return out;
	}

	/** A table put down at the base stays there for everyone. */
	private boolean atBase(BlockPos pos) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		return colony != null && colony.near(c.player().getServerWorld(), pos, 10);
	}

	private Status pickup(CompanionPlayer p) {
		if (table == null || !p.getWorld().getBlockState(table).isOf(Blocks.CRAFTING_TABLE)) {
			if (!tableBroken) {
				tableBroken = true;
				phaseTicks = 0; // the wait for the drop starts now, not when we started mining
			}
			// Wait a moment for the dropped table to be picked up.
			if (phaseTicks > 60 || Inventories.count(p.getInventory(), s -> s.isOf(Items.CRAFTING_TABLE)) > 0) {
				return Status.SUCCESS;
			}
			ItemEntity drop = p.getServerWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(4.0),
				e -> e.getStack().isOf(Items.CRAFTING_TABLE)).stream().findFirst().orElse(null);
			if (drop != null) {
				c.actions().moveTo(drop.getPos());
			}
			return Status.RUNNING;
		}
		if (c.actions().mineBlock(table) == com.botcraft.action.ActionController.MineResult.FAILED || phaseTicks > 20 * 10) {
			return Status.SUCCESS; // leave it there; not worth failing the craft over
		}
		return Status.RUNNING;
	}

	// ---- helpers --------------------------------------------------------------------------------

	private boolean askedFriend;

	/** Delivery key for an item (any kind of log counts as "logs"). */
	static String shareKey(Item item) {
		if (new ItemStack(item).isIn(ItemTags.LOGS)) {
			return "logs";
		}
		var ore = com.botcraft.knowledge.OreKind.byDrop(item);
		return ore != null ? ore.word : ItemNames.id(item);
	}

	public static Map<Item, Integer> inventoryCounts(PlayerInventory inv) {
		Map<Item, Integer> m = new HashMap<>();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) {
				m.merge(s.getItem(), s.getCount(), Integer::sum);
			}
		}
		return m;
	}

	private static boolean hasTableItem(CompanionPlayer p) {
		return Inventories.count(p.getInventory(), s -> s.isOf(Items.CRAFTING_TABLE)) > 0;
	}

	/** A crafting table within 16 blocks (remembered ones are checked by the caller via memory). */
	@Nullable
	private BlockPos findTable(CompanionPlayer p) {
		BlockPos center = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(center.add(-8, -3, -8), center.add(8, 3, 8))) {
			if (p.getWorld().getBlockState(pos).isOf(Blocks.CRAFTING_TABLE) && !badTables.contains(pos)) {
				double d = pos.getSquaredDistance(center);
				if (d < bestD) {
					bestD = d;
					best = pos.toImmutable();
				}
			}
		}
		if (best == null) {
			var place = c.memory().place("crafting_table");
			if (place != null && place.dim.equals(p.getWorld().getRegistryKey().getValue().toString())
				&& place.pos().getSquaredDistance(center) < 32 * 32 && p.getWorld().getBlockState(place.pos()).isOf(Blocks.CRAFTING_TABLE)
				&& !badTables.contains(place.pos())) {
				best = place.pos();
			}
		}
		if (best == null) {
			best = atBaseFeature(p, com.botcraft.colony.BaseMap.Feature.CRAFTING_TABLE,
				b -> !badTables.contains(b) && p.getWorld().getBlockState(b).isOf(Blocks.CRAFTING_TABLE));
		}
		return best;
	}

	/**
	 * At the base: the base's own one of these (from the shared base map), so companions use the
	 * table or furnace that's already there instead of putting down another.
	 */
	@org.jetbrains.annotations.Nullable
	static BlockPos atBaseFeature(CompanionPlayer p, com.botcraft.colony.BaseMap.Feature f, java.util.function.Predicate<BlockPos> ok) {
		var colony = com.botcraft.colony.Colony.get();
		var map = com.botcraft.colony.BaseMap.get();
		if (colony == null || map == null || !colony.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS)
			|| !com.botcraft.util.Experiments.on("baseMapReuse")) {
			return null;
		}
		BlockPos near = map.nearest(f, p.getBlockPos(), 24);
		return near != null && ok.test(near) ? near : null;
	}

}
