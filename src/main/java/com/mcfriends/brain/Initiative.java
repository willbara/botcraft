package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * First step toward autonomy: when a companion has nothing to do (no orders, no danger,
 * daytime, owner nearby and not wandering off) it sometimes picks a goal of its own: get
 * armed, get a pickaxe, upgrade to stone tools, keep some wood around. It says what it's doing
 * and queues ordinary jobs, so any order from the owner simply replaces them.
 *
 * <p>How often is set by the {@code initiative} config (0 = never) and the companion's
 * curiosity and helpfulness.
 */
public class Initiative {
	private static final int CHECK_EVERY = 200;
	private static final int COOLDOWN = 20 * 60 * 3;

	private final Random random = new Random();
	private long nextAllowed;

	public void tick(Companion c, long now) {
		float level = CompanionConfig.get().initiative;
		if (level <= 0 || now % CHECK_EVERY != 0 || now < nextAllowed) {
			return;
		}
		CompanionPlayer p = c.player();
		if (!"follow".equals(c.data.mode) || !c.jobs().isIdle() || !c.perception().threats().isEmpty()
			|| c.perception().isNight() || c.perception().ownerDistance() > 24) {
			return;
		}
		float chance = level * (0.4F + 0.3F * c.data.curiosity + 0.3F * c.data.helpfulness);
		if (random.nextFloat() > chance) {
			return;
		}
		Ambition a = choose(p.getInventory());
		if (a == null) {
			nextAllowed = now + COOLDOWN;
			return;
		}
		nextAllowed = now + COOLDOWN;
		AiLog.log(c.name(), "initiative: {} ({})", a.job(), a.reason());
		c.jobs().append(List.of(a.job()));
		c.speech().event("event.initiative", Map.of("plan", Jobs.describe(a.job()), "reason", a.reason()), 0, 0.8F);
	}

	private record Ambition(String job, String reason) {
	}

	private static Ambition choose(PlayerInventory inv) {
		double weapon = Inventories.bestWeaponDamage(inv);
		boolean pickaxe = has(inv, s -> s.getItem() instanceof PickaxeItem);
		boolean stonePickaxe = has(inv, s -> s.isOf(Items.STONE_PICKAXE) || s.isOf(Items.IRON_PICKAXE) || s.isOf(Items.DIAMOND_PICKAXE)
			|| s.isOf(Items.NETHERITE_PICKAXE));
		if (weapon < 4.0) {
			return new Ambition("craft wooden_sword 1", "I don't want to be caught without a weapon");
		}
		if (!pickaxe) {
			return new Ambition("craft wooden_pickaxe 1", "a pickaxe will come in handy");
		}
		if (!stonePickaxe) {
			return new Ambition("craft stone_pickaxe 1", "time to upgrade my tools");
		}
		if (weapon < 5.0) {
			return new Ambition("craft stone_sword 1", "a better sword would be nice");
		}
		if (Inventories.count(inv, Inventories::isLog) < 4) {
			return new Ambition("gather logs 12", "we could use some wood");
		}
		return null;
	}

	private static boolean has(PlayerInventory inv, java.util.function.Predicate<ItemStack> which) {
		return Inventories.count(inv, which) > 0;
	}
}
