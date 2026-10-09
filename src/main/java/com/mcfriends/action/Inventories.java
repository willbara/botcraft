package com.mcfriends.action;

import net.minecraft.block.BlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityGroup;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Set;
import java.util.function.Predicate;

/** Inventory queries and hotbar management. Slots 0-8 are the hotbar, 9-35 main inventory. */
public final class Inventories {
	/** Blocks we are happy to place as scaffolding / bridges. */
	public static final Set<Item> THROWAWAY = Set.of(
		Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.STONE, Items.ANDESITE,
		Items.DIORITE, Items.GRANITE, Items.TUFF, Items.SANDSTONE);
	/** Food we only eat when starving. */
	private static final Set<Item> BAD_FOOD = Set.of(
		Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHICKEN,
		Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);

	private Inventories() {
	}

	public static int find(PlayerInventory inv, Predicate<ItemStack> which) {
		// Prefer the hotbar so we don't shuffle the inventory needlessly.
		for (int i = 0; i < 9; i++) {
			if (!inv.getStack(i).isEmpty() && which.test(inv.getStack(i))) {
				return i;
			}
		}
		for (int i = 9; i < inv.main.size(); i++) {
			if (!inv.getStack(i).isEmpty() && which.test(inv.getStack(i))) {
				return i;
			}
		}
		return -1;
	}

	public static int count(PlayerInventory inv, Predicate<ItemStack> which) {
		int n = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && which.test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public static boolean isThrowaway(ItemStack s) {
		return THROWAWAY.contains(s.getItem());
	}

	public static boolean isLog(ItemStack s) {
		return s.isIn(ItemTags.LOGS);
	}

	/**
	 * Select an inventory slot as the main hand. Main-inventory slots are swapped into the
	 * hotbar first (into an empty hotbar slot if there is one, else the currently selected one).
	 */
	public static void select(ServerPlayerEntity p, int slot) {
		PlayerInventory inv = p.getInventory();
		if (slot < 0) {
			return;
		}
		if (slot >= 9) {
			int target = -1;
			for (int i = 0; i < 9; i++) {
				if (inv.getStack(i).isEmpty()) {
					target = i;
					break;
				}
			}
			if (target < 0) {
				target = inv.selectedSlot;
			}
			ItemStack a = inv.getStack(target);
			inv.setStack(target, inv.getStack(slot));
			inv.setStack(slot, a);
			slot = target;
		}
		inv.selectedSlot = slot;
	}

	/** Pick the fastest tool for this block (bare hand if nothing helps). */
	public static void equipBestTool(ServerPlayerEntity p, BlockState state) {
		PlayerInventory inv = p.getInventory();
		int best = -1;
		float bestScore = toolScore(inv.getMainHandStack(), state);
		for (int i = 0; i < inv.main.size(); i++) {
			float score = toolScore(inv.getStack(i), state);
			if (score > bestScore + 0.01F) {
				bestScore = score;
				best = i;
			}
		}
		if (best >= 0) {
			select(p, best);
		}
	}

	private static float toolScore(ItemStack s, BlockState state) {
		if (s.isEmpty()) {
			return 1.0F;
		}
		if (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 2) {
			return 0.0F; // don't break nearly-broken tools
		}
		float speed = s.getMiningSpeedMultiplier(state);
		if (state.isToolRequired() && s.isSuitableFor(state)) {
			speed += 100.0F; // the right tool is needed for drops
		}
		if (s.isDamageable() && speed <= 1.0F) {
			return 0.5F; // a useless tool would just take durability
		}
		return speed;
	}

	/** Melee damage of a stack in the main hand (base 1 + item modifiers + Sharpness etc). */
	public static double attackDamage(ItemStack s) {
		double dmg = 1.0;
		for (EntityAttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
			if (m.getOperation() == EntityAttributeModifier.Operation.ADDITION) {
				dmg += m.getValue();
			}
		}
		return dmg + EnchantmentHelper.getAttackDamage(s, EntityGroup.DEFAULT);
	}

	/** Equip the highest-damage weapon. Returns its damage. */
	public static double equipBestWeapon(ServerPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		int best = -1;
		double bestDmg = attackDamage(inv.getMainHandStack());
		for (int i = 0; i < inv.main.size(); i++) {
			double d = attackDamage(inv.getStack(i));
			if (d > bestDmg + 0.01) {
				bestDmg = d;
				best = i;
			}
		}
		if (best >= 0) {
			select(p, best);
		}
		return bestDmg;
	}

	public static double bestWeaponDamage(PlayerInventory inv) {
		double best = 1.0;
		for (int i = 0; i < inv.main.size(); i++) {
			best = Math.max(best, attackDamage(inv.getStack(i)));
		}
		return best;
	}

	/** Protection value used to compare armour pieces (armour points, toughness, then enchantments). */
	private static double armorScore(ItemStack s) {
		if (!(s.getItem() instanceof ArmorItem armor)) {
			return -1;
		}
		return armor.getProtection() * 10.0 + armor.getToughness() * 3.0 + EnchantmentHelper.getLevel(Enchantments.PROTECTION, s);
	}

	/**
	 * Wear the best armour carried. Swaps with what's worn, like a player shift-clicking.
	 * Items with Curse of Binding are never put on.
	 *
	 * @return true if anything changed
	 */
	public static boolean equipBestArmor(ServerPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		boolean changed = false;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!(s.getItem() instanceof ArmorItem armor) || EnchantmentHelper.hasBindingCurse(s)) {
				continue;
			}
			EquipmentSlot slot = armor.getSlotType();
			ItemStack worn = p.getEquippedStack(slot);
			if (EnchantmentHelper.hasBindingCurse(worn) || armorScore(s) <= armorScore(worn)) {
				continue;
			}
			p.equipStack(slot, s.copy());
			inv.setStack(i, worn.copy());
			changed = true;
		}
		return changed;
	}

	/**
	 * Best food slot, or -1. Normal food scores by hunger + saturation restored; "bad" food
	 * (poison/hunger effects) only when {@code starving}.
	 */
	public static int bestFood(PlayerInventory inv, boolean starving) {
		int best = -1;
		float bestScore = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			FoodComponent food = s.getItem().getFoodComponent();
			if (food == null) {
				continue;
			}
			if (BAD_FOOD.contains(s.getItem()) && !starving) {
				continue;
			}
			float score = food.getHunger() + food.getHunger() * food.getSaturationModifier() * 2.0F;
			if (BAD_FOOD.contains(s.getItem())) {
				score *= 0.2F;
			}
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	public static int foodCount(PlayerInventory inv) {
		return count(inv, s -> s.getItem().getFoodComponent() != null && !BAD_FOOD.contains(s.getItem()));
	}
}
