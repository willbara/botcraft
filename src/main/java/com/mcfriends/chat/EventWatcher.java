package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.entity.mob.CreeperEntity;

import java.util.Map;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Watches for meaningful moments and makes the companion remark on them unprompted:
 * nightfall, morning, low health, a creeper nearby, starving without food. (Task done/failed,
 * kills, fights and (re)joining are reported from the corresponding callbacks.)
 */
public class EventWatcher {
	private static final int SECOND = 20;
	private boolean wasNight;
	private boolean wasLowHealth;
	private boolean sawCreeper;
	private boolean initialized;
	private boolean wasRaining;
	private boolean wasThundering;
	private String lastBiome = "";
	private boolean ownerWasHurt;
	private boolean ownerWasDead;
	private final java.util.Random random = new java.util.Random();

	public void tick(Companion c, long serverTick) {
		if (serverTick % 10 != 0) {
			return;
		}
		CompanionPlayer p = c.player();
		if (p == null || p.isDead()) {
			return;
		}
		Speech s = c.speech();
		boolean night = c.perception().isNight();
		boolean low = p.getHealth() < p.getMaxHealth() * 0.3F;
		CreeperEntity creeper = c.perception().nearestCreeper(10.0);
		if (initialized) {
			if (night && !wasNight) {
				s.event("event.night", Map.of(), 60 * SECOND, 0.6F);
			} else if (!night && wasNight) {
				s.event("event.morning", Map.of(), 60 * SECOND, 0.2F);
			}
			if (low && !wasLowHealth) {
				s.event("event.low_health", Map.of(), 45 * SECOND, 0.9F);
			}
			if (creeper != null && !sawCreeper) {
				s.event("event.creeper", Map.of(), 20 * SECOND, 0.9F);
			}
		}
		if (p.getHungerManager().getFoodLevel() <= 6 && c.perception().inventory().food() == 0
			&& Inventories.bestFood(p.getInventory(), true) < 0) {
			s.event("event.hungry", Map.of(), 300 * SECOND, 0.7F);
		}
		// Weather.
		boolean raining = p.getWorld().isRaining();
		boolean thundering = p.getWorld().isThundering();
		if (initialized && thundering && !wasThundering) {
			s.event("event.thunder", Map.of(), 300 * SECOND, 0.5F);
		} else if (initialized && raining && !wasRaining) {
			s.event("event.rain", Map.of(), 300 * SECOND, 0.3F);
		}
		wasRaining = raining;
		wasThundering = thundering;

		// New biome (first visit only, remembered).
		String biome = p.getWorld().getBiome(p.getBlockPos()).getKey().map(k -> k.getValue().getPath()).orElse("");
		if (!biome.equals(lastBiome)) {
			if (initialized && !biome.isEmpty() && c.memory().stat("biome_" + biome) == 0) {
				s.event("event.biome", Map.of("biome", biome.replace('_', ' ')), 120 * SECOND, 0.4F);
			}
			if (!biome.isEmpty()) {
				c.memory().count("biome_" + biome, 1);
			}
			lastBiome = biome;
		}

		// The owner's wellbeing.
		var owner = c.perception().owner();
		var ownerAny = c.owner();
		if (ownerAny != null && ownerAny.getWorld() == p.getWorld() && ownerAny.distanceTo(p) < 32) {
			boolean dead = ownerAny.isDead();
			boolean hurt = !dead && ownerAny.getHealth() < 6.0F;
			if (initialized && dead && !ownerWasDead) {
				s.event("event.owner_died", Map.of("player", ownerAny.getGameProfile().getName()), 30 * SECOND, 0.9F);
			} else if (initialized && hurt && !ownerWasHurt && owner != null) {
				s.event("event.owner_hurt", Map.of("player", ownerAny.getGameProfile().getName()), 60 * SECOND, 0.6F);
			}
			ownerWasDead = dead;
			ownerWasHurt = hurt;
		}

		// Deep underground: shout about diamonds we can see (and remember them).
		if (serverTick % 200 == 0 && p.getBlockY() < 16) {
			BlockPos found = visibleDiamonds(p);
			if (found != null) {
				c.memory().sight("diamond", found, c.dimension(), p.getWorld().getTime());
				s.event("event.diamonds", Map.of(), 600 * SECOND, 0.9F);
			}
		}

		// Idle musings.
		if (initialized && serverTick % 200 == 0 && c.jobs().isIdle() && !c.nav().isBusy()
			&& random.nextFloat() < 0.04F * c.data.chattiness) {
			s.event("event.muse", Map.of(), 600 * SECOND, 0.0F);
		}

		wasNight = night;
		wasLowHealth = low;
		sawCreeper = creeper != null;
		initialized = true;
	}

	/** A diamond ore block within 8 blocks that touches air (so we could actually see it). */
	@Nullable
	private static BlockPos visibleDiamonds(CompanionPlayer p) {
		BlockPos c = p.getBlockPos();
		for (BlockPos pos : BlockPos.iterate(c.add(-8, -4, -8), c.add(8, 4, 8))) {
			var st = p.getWorld().getBlockState(pos);
			if (st.isOf(Blocks.DIAMOND_ORE) || st.isOf(Blocks.DEEPSLATE_DIAMOND_ORE)) {
				for (Direction d : Direction.values()) {
					if (p.getWorld().getBlockState(pos.offset(d)).isAir()) {
						return pos.toImmutable();
					}
				}
			}
		}
		return null;
	}
}
