package com.botcraft.brain.plan;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterials;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Progression as data: what a companion should have, tier by tier. Free mode works through them
 * in order, asking the {@link Planner} how to get the first thing still missing. Adding a tier is
 * adding a line here, not writing new behaviour.
 */
public final class Targets {
	/** One thing to have: done when {@code have} holds, otherwise get {@code item}. */
	public record Target(String tier, String name, Item item, Predicate<CompanionPlayer> have) {
	}

	public static final List<Target> LADDER = List.of(
		new Target("iron tools", "an iron pickaxe", Items.IRON_PICKAXE, p -> toolTier(p, s -> s.getItem() instanceof PickaxeItem) >= 3),
		new Target("iron tools", "an iron sword", Items.IRON_SWORD, p -> Inventories.bestWeaponDamage(p.getInventory()) >= 6.0),
		new Target("iron armour", "an iron chestplate", Items.IRON_CHESTPLATE, p -> armour(p, EquipmentSlot.CHEST) >= 6),
		new Target("iron armour", "iron leggings", Items.IRON_LEGGINGS, p -> armour(p, EquipmentSlot.LEGS) >= 5),
		new Target("iron armour", "an iron helmet", Items.IRON_HELMET, p -> armour(p, EquipmentSlot.HEAD) >= 2),
		new Target("iron armour", "iron boots", Items.IRON_BOOTS, p -> armour(p, EquipmentSlot.FEET) >= 2),
		new Target("diamond tools", "a diamond pickaxe", Items.DIAMOND_PICKAXE, p -> toolTier(p, s -> s.getItem() instanceof PickaxeItem) >= 4),
		new Target("diamond tools", "a diamond sword", Items.DIAMOND_SWORD, p -> Inventories.bestWeaponDamage(p.getInventory()) >= 7.0),
		new Target("diamond armour", "a diamond chestplate", Items.DIAMOND_CHESTPLATE, p -> armour(p, EquipmentSlot.CHEST) >= 8),
		new Target("diamond armour", "diamond leggings", Items.DIAMOND_LEGGINGS, p -> armour(p, EquipmentSlot.LEGS) >= 6),
		new Target("diamond armour", "a diamond helmet", Items.DIAMOND_HELMET, p -> armour(p, EquipmentSlot.HEAD) >= 3),
		new Target("diamond armour", "diamond boots", Items.DIAMOND_BOOTS, p -> armour(p, EquipmentSlot.FEET) >= 3));

	private Targets() {
	}

	/** The first target not yet met (null when everything on the ladder is done). */
	@Nullable
	public static Target next(Companion c, Predicate<Target> allowed) {
		CompanionPlayer p = c.player();
		for (Target t : LADDER) {
			if (!t.have().test(p) && allowed.test(t)) {
				return t;
			}
		}
		return null;
	}

	/** Protection of what's worn in a slot, or of a carried piece for it (it'll be put on). */
	private static int armour(CompanionPlayer p, EquipmentSlot slot) {
		int best = p.getEquippedStack(slot).getItem() instanceof ArmorItem a ? a.getProtection() : 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			if (inv.getStack(i).getItem() instanceof ArmorItem a && a.getSlotType() == slot) {
				best = Math.max(best, a.getProtection());
			}
		}
		return best;
	}

	/** Best tool tier of a kind carried: 1 wood/gold, 2 stone, 3 iron, 4 diamond, 5 netherite. */
	private static int toolTier(CompanionPlayer p, Predicate<ItemStack> kind) {
		int best = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !kind.test(s) || !(s.getItem() instanceof ToolItem t)) {
				continue;
			}
			var m = t.getMaterial();
			int tier = m == ToolMaterials.NETHERITE ? 5 : m == ToolMaterials.DIAMOND ? 4 : m == ToolMaterials.IRON ? 3 : m == ToolMaterials.STONE ? 2 : 1;
			best = Math.max(best, tier);
		}
		return best;
	}
}
