package com.mcfriends.knowledge;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Huntable animals and the rules that keep companions from wrecking the owner's farm. */
public final class Animals {
	public static final Set<EntityType<?>> FOOD_ANIMALS = Set.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
		EntityType.RABBIT);
	public static final Set<Item> RAW_MEAT = Set.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT, Items.COD,
		Items.SALMON);

	private Animals() {
	}

	/** Species for a chat word ("cows", "pork", "meat"); empty list = any food animal. */
	@Nullable
	public static List<EntityType<?>> byWord(String phrase) {
		String p = phrase.toLowerCase(Locale.ROOT).trim();
		if (p.isEmpty() || p.matches("(some |the )?(food|meat|animals?|something|dinner|lunch|breakfast)")) {
			return List.of();
		}
		if (p.matches("cows?|beef|steak|leather")) {
			return List.of(EntityType.COW);
		}
		if (p.matches("pigs?|pork|porkchops?|bacon")) {
			return List.of(EntityType.PIG);
		}
		if (p.matches("sheep|mutton|lamb|wool")) {
			return List.of(EntityType.SHEEP);
		}
		if (p.matches("chickens?|feathers?|poultry")) {
			return List.of(EntityType.CHICKEN);
		}
		if (p.matches("rabbits?|bunn(y|ies)")) {
			return List.of(EntityType.RABBIT);
		}
		return null;
	}

	/**
	 * Fair game: adult, not named or leashed, not penned in by fences (someone's farm), and not one
	 * of the last two of its kind around (leave a breeding pair).
	 */
	public static boolean huntable(AnimalEntity a, List<? extends AnimalEntity> sameKindNearby) {
		if (!a.isAlive() || a.isBaby() || a.hasCustomName() || a.isLeashed()) {
			return false;
		}
		long adults = sameKindNearby.stream().filter(o -> o.getType() == a.getType() && !o.isBaby() && o.isAlive()).count();
		return adults > 2 && !penned(a.getWorld(), a.getBlockPos());
	}

	private static boolean penned(World world, BlockPos pos) {
		int fences = 0;
		for (BlockPos b : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 1, 3))) {
			var s = world.getBlockState(b);
			if (s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.FENCE_GATES) || s.isIn(BlockTags.WALLS)) {
				if (++fences >= 3) {
					return true;
				}
			}
		}
		return false;
	}
}
