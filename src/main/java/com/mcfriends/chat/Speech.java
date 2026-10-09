package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Outgoing chat for one companion. Lines are delayed as if typed, spaced out, and rate
 * limited; unprompted remarks have per-event cooldowns and are skipped at random depending on
 * chattiness. Messages are broadcast exactly like player chat ("&lt;Name&gt; text") but as
 * unsigned system messages, since a fake player has no chat signing key.
 */
public class Speech {
	private static final int MIN_GAP_TICKS = 25;

	private record Line(String key, String text, long due) {
	}

	private final Companion c;
	private final Random random = new Random();
	private final ArrayDeque<Line> queue = new ArrayDeque<>();
	private final Map<String, Long> cooldowns = new HashMap<>();
	private long now;
	private long lastSpoke = -1000;
	private final java.util.List<String> spokenKeys = new java.util.ArrayList<>();

	public Speech(Companion c) {
		this.c = c;
	}

	public void tick(long serverTick) {
		now = serverTick;
		Line next = queue.peek();
		if (next != null && now >= next.due() && now - lastSpoke >= MIN_GAP_TICKS) {
			queue.poll();
			broadcast(next.text());
			spokenKeys.add(next.key());
			if (spokenKeys.size() > 20) {
				spokenKeys.remove(0);
			}
			lastSpoke = now;
		}
	}

	/** A reply to someone: always said (if chat is enabled). */
	public void reply(String key, @Nullable ServerPlayerEntity to, Map<String, String> extra) {
		say(key, to, extra);
	}

	/**
	 * An unprompted remark. {@code importance} 1 = always (subject to cooldown), 0 = only very
	 * chatty companions. Returns true if queued.
	 */
	public boolean event(String key, Map<String, String> extra, int cooldownTicks, float importance) {
		Long until = cooldowns.get(key);
		if (until != null && now < until) {
			return false;
		}
		cooldowns.put(key, now + cooldownTicks);
		float chance = importance + c.data.chattiness * (1.0F - importance);
		if (random.nextFloat() > chance) {
			AiLog.log(c.name(), "chose not to say {}", key);
			return false;
		}
		return say(key, c.owner(), extra);
	}

	private boolean say(String key, @Nullable ServerPlayerEntity to, Map<String, String> extra) {
		if (!CompanionConfig.get().chatEnabled || queue.size() > 4) {
			return false;
		}
		Map<String, String> vars = vars(to);
		vars.putAll(extra);
		String variant = c.data.bravery > 0.65F ? "brave" : c.data.bravery < 0.35F ? "timid" : "";
		String text = Templates.pick(key, variant, vars, random);
		if (text.isEmpty()) {
			return false;
		}
		int typing = 8 + text.length() / 2 + random.nextInt(10);
		queue.add(new Line(key, text, now + typing));
		AiLog.log(c.name(), "will say [{}] '{}'", key, text);
		return true;
	}

	private void broadcast(String text) {
		CompanionPlayer p = c.player();
		if (p == null || p.isDead()) {
			return;
		}
		c.server.getPlayerManager().broadcast(Text.translatable("chat.type.text", p.getDisplayName(), Text.literal(text)), false);
	}

	/** Template keys of recent lines actually said (for tests and debugging). */
	public java.util.List<String> spokenKeys() {
		return spokenKeys;
	}

	/** Placeholder values from live game state. */
	Map<String, String> vars(@Nullable ServerPlayerEntity to) {
		Map<String, String> v = new HashMap<>();
		CompanionPlayer p = c.player();
		v.put("name", c.name());
		v.put("owner", c.data.ownerName != null ? c.data.ownerName : "nobody");
		v.put("player", to != null ? to.getGameProfile().getName() : (c.data.ownerName != null ? c.data.ownerName : "friend"));
		if (p != null) {
			int hp = Math.round(p.getHealth());
			int food = p.getHungerManager().getFoodLevel();
			v.put("health", Integer.toString(hp));
			v.put("food", Integer.toString(food));
			v.put("logs", Integer.toString(Inventories.count(p.getInventory(), Inventories::isLog)));
			v.put("task", c.describeActivity());
			v.put("mood", hp >= 18 && food >= 16 ? "great" : hp < 8 ? "pretty beat up" : food < 8 ? "hungry" : "okay");
			v.put("time_note", c.perception().isNight() ? "It's night, careful." : "");
		}
		return v;
	}
}
