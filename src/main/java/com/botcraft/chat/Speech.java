package com.botcraft.chat;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
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
	/** Raw template lines said recently, so we don't repeat ourselves. */
	private final ArrayDeque<String> recentLines = new ArrayDeque<>();
	private Voice voice;

	public Speech(Companion c) {
		this.c = c;
	}

	public Voice voice() {
		if (voice == null) {
			voice = Voice.of(c.data.uuid.getLeastSignificantBits() ^ c.data.name.hashCode(), c.data.chattiness, c.data.bravery);
		}
		return voice;
	}

	public void tick(long serverTick) {
		now = serverTick;
		Line next = null;
		for (Line l : queue) {
			if (next == null || l.due() < next.due()) {
				next = l;
			}
		}
		if (next != null && now >= next.due() && now - lastSpoke >= MIN_GAP_TICKS) {
			queue.remove(next);
			broadcast(next.text());
			spokenKeys.add(next.key());
			if (spokenKeys.size() > 20) {
				spokenKeys.remove(0);
			}
			lastSpoke = now;
		}
	}

	/** A reply to someone: always said (if chat is enabled), quiet or not. */
	public void reply(String key, @Nullable ServerPlayerEntity to, Map<String, String> extra) {
		replying = true;
		try {
			say(key, to, extra);
		} finally {
			replying = false;
		}
	}

	/** Saying a reply right now (quiet chat lets those through). */
	private boolean replying;

	/** Unprompted lines still said with quiet chat: starting something of our own, and failures. */
	private static final java.util.Set<String> QUIET_KEEPS = java.util.Set.of("event.initiative", "board.take", "event.task_failed",
		"event.plan_failed");

	/** Quiet chat (/companion chat quiet): only replies and {@link #QUIET_KEEPS} get said. */
	private boolean quietSkips(String key) {
		return "quiet".equals(CompanionConfig.get().chatVerbosity) && !replying && !QUIET_KEEPS.contains(key);
	}

	/** What a member still says when only the leader talks (see {@link #factionHushed}): answering its orders. */
	public static final java.util.Set<String> MEMBER_KEEPS = java.util.Set.of("faction.order_ack", "faction.checkin_reply",
		"faction.order_done", "faction.order_failed");
	/** What the one in charge says when only the leader talks: the faction's business. */
	private static final java.util.List<String> LEADER_PREFIXES = java.util.List.of("faction.", "bounty.", "standing.", "raid.",
		"colony.", "found.", "board.");

	/**
	 * In a faction with leader-only chat (the default, /companion chat factions): the leader (or
	 * whoever speaks for the faction while it's away) says only the faction's business (orders,
	 * check-ins, wars, news), and members only answer its orders and check-ins. Replies to players
	 * always get through, and so do the easter eggs' lines. The factionless and single mode talk as
	 * they always have.
	 */
	private boolean factionHushed(String key) {
		if (replying || !"leader".equals(CompanionConfig.get().factionChat) || !com.botcraft.faction.Factions.enabled()
			|| key.startsWith("egg.")) {
			return false;
		}
		String id = com.botcraft.faction.Factions.idOf(c);
		if (com.botcraft.faction.Factions.loners(id) || com.botcraft.faction.Factions.get(id) == null) {
			return false;
		}
		if (MEMBER_KEEPS.contains(key)) {
			return false;
		}
		if (com.botcraft.faction.Diplomacy.spokesperson(id) == c) {
			for (String prefix : LEADER_PREFIXES) {
				if (key.startsWith(prefix)) {
					return false;
				}
			}
		}
		return true;
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
		return sayIn(key, to, extra, 0);
	}

	/** Say something after {@code delay} ticks (plus typing time): used for back-and-forth banter. */
	public boolean sayIn(String key, @Nullable ServerPlayerEntity to, Map<String, String> extra, int delay) {
		if (!CompanionConfig.get().chatEnabled || queue.size() > 5 || quietSkips(key)) {
			return false;
		}
		if (factionHushed(key)) {
			AiLog.log(c.name(), "kept quiet ({}): the leader does the talking", key);
			return false;
		}
		Map<String, String> vars = vars(to);
		vars.putAll(extra);
		// The mood shows first (if there are lines for it), else the character.
		String feeling = c.mood().variant();
		String variant = !feeling.isEmpty() && Templates.has(key + "." + feeling) ? feeling
			: c.data.bravery > 0.65F ? "brave" : c.data.bravery < 0.35F ? "timid" : "";
		String raw = Templates.pick(key, variant, vars, random, recentLines::contains);
		if (raw.isEmpty()) {
			return false;
		}
		recentLines.addLast(raw);
		while (recentLines.size() > 40) {
			recentLines.removeFirst();
		}
		String text = voice().apply(Templates.fill(raw, vars), random);
		int typing = 8 + text.length() / 2 + random.nextInt(10);
		queue.add(new Line(key, text, now + delay + typing));
		AiLog.log(c.name(), "will say [{}] '{}'", key, text);
		return true;
	}

	private void broadcast(String text) {
		CompanionPlayer p = c.player();
		if (p == null || p.isDead() || com.botcraft.brain.EasterEggs.silent(c)) {
			return; // (Herobrine and Hausemaster never say a word)
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
		v.put("nick", voice().nickFor(v.get("player"), random));
		if (p != null) {
			int hp = Math.round(p.getHealth());
			int food = p.getHungerManager().getFoodLevel();
			v.put("health", Integer.toString(hp));
			v.put("food", Integer.toString(food));
			v.put("logs", Integer.toString(Inventories.count(p.getInventory(), Inventories::isLog)));
			v.put("task", c.describeActivity());
			String feeling = c.mood().describe();
			v.put("mood", hp < 8 ? "pretty beat up" : food < 8 ? "hungry" : !feeling.isEmpty() ? feeling : hp >= 18 && food >= 16 ? "great" : "okay");
			v.put("time_note", c.perception().isNight() ? "It's night, careful." : "");
		}
		return v;
	}
}
