package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.util.AiLog;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Orders from /companion task and shared helpers. The task text goes through the same parser and
 * router as chat, so "/companion task Bob get logs then craft a wooden pickaxe" works exactly like
 * saying it.
 */
public final class CommandTasks {
	public static final List<String> EXAMPLES = List.of("follow", "stay", "come", "defend", "gather 16 logs",
		"mine 8 coal", "craft a stone pickaxe", "bring 10 logs", "go home", "sleep", "cancel", "status");

	private CommandTasks() {
	}

	/** @return null on success, otherwise an error message. */
	@Nullable
	public static String apply(Companion c, String spec, @Nullable ServerPlayerEntity source) {
		IntentParser.Parsed parsed = IntentParser.parse(spec, List.of());
		if (parsed.intents().size() == 1 && parsed.intent().type() == Intent.Type.UNKNOWN) {
			return "Unknown task '" + spec + "'. Try: " + String.join(", ", EXAMPLES);
		}
		return ChatRouter.respond(c, source, parsed.intents(), parsed.append());
	}

	public static void follow(Companion c, @Nullable ServerPlayerEntity who) {
		claimOwner(c, who);
		c.data.defendOwner = false;
		c.data.mode = "follow";
		c.jobs().clear();
		c.brain().reconsider();
		AiLog.log(c.name(), "order: follow");
	}

	public static void defend(Companion c, @Nullable ServerPlayerEntity who) {
		follow(c, who);
		c.data.defendOwner = true;
		AiLog.log(c.name(), "order: defend owner");
	}

	public static void stay(Companion c) {
		c.data.mode = "stay";
		c.data.defendOwner = false;
		c.jobs().clear();
		c.brain().clearAll();
		AiLog.log(c.name(), "order: stay");
	}

	/** Item words that mean wood. */
	public static boolean isWoodWord(String item) {
		return item.matches("(oak |birch |spruce |jungle |acacia |dark oak |mangrove |cherry )?(logs?|wood|woods|trees?|timber)|oak|birch|spruce|jungle|acacia");
	}

	/** Queue jobs to get items, like the chat "get N X" (tests and commands). @return error or null */
	@Nullable
	public static String gather(Companion c, String item, int count, @Nullable ServerPlayerEntity who) {
		List<String> specs = new ArrayList<>();
		String problem = ChatRouter.acquire(c, new Intent(Intent.Type.GATHER, item, count), specs);
		if (problem != null) {
			return problem;
		}
		claimOwner(c, who);
		c.jobs().replace(specs);
		return null;
	}

	/** A companion without an owner adopts whoever gives it an order. */
	static void claimOwner(Companion c, @Nullable ServerPlayerEntity who) {
		if (who != null && c.data.owner == null) {
			c.data.owner = who.getUuid();
			c.data.ownerName = who.getGameProfile().getName();
		}
	}
}
