package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.brain.task.GatherLogsTask;
import com.mcfriends.brain.task.GoToTask;
import com.mcfriends.util.AiLog;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Applies orders (from /companion task or parsed chat intents) to a companion. Shared by the
 * command and chat paths so both behave identically.
 */
public final class CommandTasks {
	public static final List<String> EXAMPLES = List.of("follow", "stay", "come", "gather logs 16", "defend", "status");

	private CommandTasks() {
	}

	/** @return null on success, otherwise an error message. */
	@Nullable
	public static String apply(Companion c, String spec, @Nullable ServerPlayerEntity source) {
		String s = spec.trim().toLowerCase(Locale.ROOT);
		String[] words = s.split("\\s+");
		switch (words[0]) {
			case "follow" -> {
				follow(c, source);
				return null;
			}
			case "stay", "stop", "wait" -> {
				stay(c);
				return null;
			}
			case "come" -> {
				if (source == null) {
					return "Only a player can call a companion over";
				}
				comeHere(c, source);
				return null;
			}
			case "status" -> {
				return null;
			}
			case "gather", "get", "chop", "collect", "fetch" -> {
				String item = words.length > 1 ? words[1] : "logs";
				int count = 16;
				for (String w : words) {
					if (w.matches("\\d{1,4}")) {
						count = Integer.parseInt(w);
					}
				}
				if (words.length > 2 && words[1].matches("\\d{1,4}")) {
					item = words[2];
				}
				return gather(c, item, count, source);
			}
			default -> {
				return "Unknown task '" + spec + "'. Try: " + String.join(", ", EXAMPLES);
			}
		}
	}

	public static void follow(Companion c, @Nullable ServerPlayerEntity who) {
		claimOwner(c, who);
		c.data.mode = "follow";
		c.brain().setJob(null);
		c.brain().reconsider();
		AiLog.log(c.name(), "order: follow");
	}

	public static void stay(Companion c) {
		c.data.mode = "stay";
		c.data.defendOwner = false;
		c.brain().clearAll();
		AiLog.log(c.name(), "order: stay");
	}

	public static void comeHere(Companion c, ServerPlayerEntity who) {
		claimOwner(c, who);
		c.brain().setJob(new GoToTask(c, who.getBlockPos(), "over to " + who.getGameProfile().getName()));
		c.data.job = null; // transient, not restored after restart
		AiLog.log(c.name(), "order: come to {}", who.getBlockPos().toShortString());
	}

	/** Item words we understand for gathering. Only wood is supported in this baseline. */
	public static boolean isWoodWord(String item) {
		return item.matches("logs?|wood|woods|trees?|timber|oak|birch|spruce|jungle|acacia");
	}

	/** @return null on success, otherwise an error message. */
	@Nullable
	public static String gather(Companion c, String item, int count, @Nullable ServerPlayerEntity who) {
		if (!isWoodWord(item)) {
			return "I only know how to gather logs so far";
		}
		claimOwner(c, who);
		count = Math.max(1, Math.min(count, 640));
		c.brain().setJob(new GatherLogsTask(c, count));
		c.data.job = "gather logs " + count;
		AiLog.log(c.name(), "order: gather {} logs", count);
		return null;
	}

	/** Restore a persisted job after a restart / respawn. */
	public static void restoreJob(Companion c) {
		String job = c.data.job;
		AiLog.log(c.name(), "restoring job '{}'", job);
		String[] w = job.trim().split("\\s+");
		if (w.length == 3 && w[0].equals("gather") && w[2].matches("\\d+")) {
			int remaining = Integer.parseInt(w[2]);
			if (remaining > 0) {
				gather(c, w[1], remaining, null);
				return;
			}
		}
		c.data.job = null;
	}

	/** A companion without an owner adopts whoever gives it an order. */
	private static void claimOwner(Companion c, @Nullable ServerPlayerEntity who) {
		if (who != null && c.data.owner == null) {
			c.data.owner = who.getUuid();
			c.data.ownerName = who.getGameProfile().getName();
		}
	}
}
