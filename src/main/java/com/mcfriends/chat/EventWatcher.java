package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.entity.mob.CreeperEntity;

import java.util.Map;

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
		wasNight = night;
		wasLowHealth = low;
		sawCreeper = creeper != null;
		initialized = true;
	}
}
