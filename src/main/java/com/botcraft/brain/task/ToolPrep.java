package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.memory.Memory;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.CraftPlanner;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.knowledge.Smelting;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Before starting work, check the right tool is on hand (axe for logs, pickaxe for mining, sword
 * for hunting) and make or upgrade it when that is easy: everything needed is already in the
 * inventory, or it's just a matter of smelting the raw iron we carry (up to iron tier). Nothing here sends anyone
 * off to gather materials; a job that truly needs a tool (mining) still asks friends and
 * gathers through its own prerequisites.
 *
 * <p>Prep jobs are optional (prefixed {@code ?}): if one fails, the real job goes ahead anyway.
 */
public final class ToolPrep {
	private enum Kind {
		AXE(s -> s.getItem() instanceof AxeItem, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE),
		PICKAXE(s -> s.getItem() instanceof PickaxeItem, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE),
		SWORD(s -> s.getItem() instanceof SwordItem, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD);

		final Predicate<ItemStack> is;
		/** By tier: 1 wood, 2 stone, 3 iron, 4 diamond. */
		final Item[] tiers;

		Kind(Predicate<ItemStack> is, Item... tiers) {
			this.is = is;
			this.tiers = tiers;
		}
	}

	/** Don't retry a prep that failed for this long. */
	private static final long FAIL_BACKOFF = 20 * 60 * 5;
	private static final Map<String, Long> FAILED = new HashMap<>();

	private ToolPrep() {
	}

	/** Prep jobs to run before {@code spec} (empty if the tools are fine or nothing easy helps). */
	public static List<String> before(Companion c, String spec) {
		String verb = spec.trim().split(" ")[0];
		Kind kind = switch (verb) {
			case "gather" -> Kind.AXE;
			case "mine" -> Kind.PICKAXE;
			case "hunt" -> Kind.SWORD;
			default -> null;
		};
		CompanionPlayer p = c.player();
		if (kind == null || p == null) {
			return List.of();
		}
		// A step toward something else (logs for a crafting table the iron pickaxe needs): its
		// materials are spoken for, so no side projects.
		for (String waiting : c.jobs().pending()) {
			if (waiting.matches(".*~\\d+(\\s+!)?$")) {
				return List.of();
			}
		}
		// Mining: a pickaxe that can't harvest the ore is no help; the job sorts that out itself.
		int minTier = 1;
		if (kind == Kind.PICKAXE) {
			String[] w = spec.trim().split(" ");
			var ore = w.length > 1 ? com.botcraft.knowledge.OreKind.byWord(w[1]) : null;
			if (ore != null) {
				minTier = switch (ore.pickaxe) {
					case "stone_pickaxe" -> 2;
					case "iron_pickaxe" -> 3;
					default -> 1;
				};
			}
		}
		// At the base with better gear in the chests (armour, or the tool this job wants): take it
		// along, whoever asked for the job.
		var colony = com.botcraft.colony.Colony.get();
		if (colony != null && colony.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS)) {
			String toolKind = switch (kind) {
				case AXE -> "axe";
				case PICKAXE -> "pickaxe";
				case SWORD -> "sword";
			};
			List<String> fromStock = new ArrayList<>();
			for (String w : com.botcraft.brain.Jobs.gearFromStock(c, java.util.Set.of(toolKind, "sword"), true)) {
				fromStock.add("?" + w);
			}
			if (!fromStock.isEmpty()) {
				return fromStock;
			}
		}
		PlayerInventory inv = p.getInventory();
		int have = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && kind.is.test(s)) {
				have = Math.max(have, tier(s));
			}
		}
		Map<Item, Integer> counts = CraftTask.inventoryCounts(inv);
		// Best tier first: the biggest easy upgrade wins. Never diamond: diamonds are usually
		// what someone asked us to collect, not ours to spend.
		for (int t = 3; t > have && t >= minTier; t--) {
			Item tool = kind.tiers[t - 1];
			String id = ItemNames.id(tool);
			Long failedAt = FAILED.get(c.name() + ":" + id);
			if (failedAt != null && c.now() - failedAt < FAIL_BACKOFF) {
				continue;
			}
			if (CraftPlanner.plan(c.server, counts, tool, 1).ready()) {
				return List.of("?craft " + id + " 1");
			}
			if (t == 3) {
				List<String> viaSmelting = ironBySmelting(c, p, inv, counts, tool);
				if (!viaSmelting.isEmpty()) {
					return viaSmelting;
				}
			}
		}
		return List.of();
	}

	/** Enough raw iron (plus fuel and a furnace) to smelt what an iron tool needs. */
	private static List<String> ironBySmelting(Companion c, CompanionPlayer p, PlayerInventory inv, Map<Item, Integer> counts, Item tool) {
		int ingots = counts.getOrDefault(Items.IRON_INGOT, 0);
		int raw = counts.getOrDefault(Items.RAW_IRON, 0);
		int need = tool == Items.IRON_SWORD ? 2 : 3;
		int smelt = need - ingots;
		if (smelt <= 0 || raw < smelt || Smelting.fuelAvailable(inv, null) < 200 * smelt) {
			return List.of();
		}
		Map<Item, Integer> after = new HashMap<>(counts);
		after.put(Items.IRON_INGOT, need);
		if (!CraftPlanner.plan(c.server, after, tool, 1).ready() || !furnaceHandy(c, p, counts)) {
			return List.of();
		}
		List<String> jobs = new ArrayList<>();
		jobs.add("?smelt iron_ingot " + smelt);
		jobs.add("?craft " + ItemNames.id(tool) + " 1");
		return jobs;
	}

	private static boolean furnaceHandy(Companion c, CompanionPlayer p, Map<Item, Integer> counts) {
		if (counts.getOrDefault(Items.FURNACE, 0) > 0 || Spots.nearest(p, Blocks.FURNACE, 12) != null) {
			return true;
		}
		Memory.Place mem = c.memory().place("furnace");
		if (mem != null && mem.pos().isWithinDistance(p.getBlockPos(), 48)) {
			return true;
		}
		return CraftPlanner.plan(c.server, counts, Items.FURNACE, 1).ready();
	}

	/** An optional prep job failed: don't try that one again for a while. */
	public static void failed(Companion c, String spec) {
		String[] w = spec.replace("?", "").trim().split(" ");
		if (w.length > 1) {
			FAILED.put(c.name() + ":" + w[1], c.now());
		}
	}

	static int tier(ItemStack s) {
		return com.botcraft.knowledge.Gear.tier(s);
	}
}
