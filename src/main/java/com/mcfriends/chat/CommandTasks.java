package com.mcfriends.chat;

import com.mcfriends.Companion;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** Parses the task argument of /companion task. */
public final class CommandTasks {
	public static final List<String> EXAMPLES = List.of("follow", "stay", "come", "gather logs 16", "defend", "status");

	private CommandTasks() {
	}

	/** @return null on success, otherwise an error message. */
	@Nullable
	public static String apply(Companion c, String spec, @Nullable ServerPlayerEntity source) {
		String s = spec.trim().toLowerCase(Locale.ROOT);
		switch (s) {
			case "follow", "stay" -> {
				c.setBaseTask(s);
				return null;
			}
			default -> {
				return "Unknown task '" + spec + "'. Try: " + String.join(", ", EXAMPLES);
			}
		}
	}
}
