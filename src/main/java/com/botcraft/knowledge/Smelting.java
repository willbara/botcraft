package com.botcraft.knowledge;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Furnace recipes and fuel values, read from the server's recipe manager. */
public final class Smelting {
	private static Map<Item, Integer> fuel;

	private Smelting() {
	}

	/** A smelting recipe making {@code output}, preferring one whose input we carry. */
	@Nullable
	public static SmeltingRecipe forOutput(MinecraftServer server, Item output, @Nullable PlayerInventory inv) {
		SmeltingRecipe any = null;
		for (SmeltingRecipe r : server.getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
			if (!r.getOutput(server.getRegistryManager()).isOf(output)) {
				continue;
			}
			if (inv != null && countInput(inv, r) > 0) {
				return r;
			}
			if (any == null) {
				any = r;
			}
		}
		return any;
	}

	/** All smelting recipes producing {@code output}. */
	public static java.util.List<SmeltingRecipe> allForOutput(MinecraftServer server, Item output) {
		java.util.List<SmeltingRecipe> out = new java.util.ArrayList<>();
		for (SmeltingRecipe r : server.getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
			if (r.getOutput(server.getRegistryManager()).isOf(output)) {
				out.add(r);
			}
		}
		return out;
	}

	/** The recipe that smelts this input, if any. */
	@Nullable
	public static SmeltingRecipe forInput(MinecraftServer server, ItemStack input) {
		for (SmeltingRecipe r : server.getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
			if (input(r).test(input)) {
				return r;
			}
		}
		return null;
	}

	public static boolean smeltable(MinecraftServer server, Item output) {
		return forOutput(server, output, null) != null;
	}

	public static Ingredient input(SmeltingRecipe r) {
		return r.getIngredients().get(0);
	}

	public static int countInput(PlayerInventory inv, SmeltingRecipe r) {
		Ingredient in = input(r);
		int n = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			if (in.test(inv.getStack(i))) {
				n += inv.getStack(i).getCount();
			}
		}
		return n;
	}

	/** Burn time in ticks (0 = not a fuel). */
	public static int fuelTicks(Item item) {
		if (fuel == null) {
			fuel = AbstractFurnaceBlockEntity.createFuelTimeMap();
		}
		return fuel.getOrDefault(item, 0);
	}

	/** Total burn ticks of fuel we carry, not counting items matching {@code except}. */
	public static int fuelAvailable(PlayerInventory inv, @Nullable Ingredient except) {
		int t = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && (except == null || !except.test(s)) && !s.isDamageable()) {
				t += fuelTicks(s.getItem()) * s.getCount();
			}
		}
		return t;
	}
}
