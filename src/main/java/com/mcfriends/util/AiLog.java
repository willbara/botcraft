package com.mcfriends.util;

import com.mcfriends.MCFriends;
import com.mcfriends.config.CompanionConfig;

/** AI decision logging: DEBUG normally, INFO when verboseAiLogging is on. */
public final class AiLog {
	private AiLog() {
	}

	public static void log(String who, String format, Object... args) {
		String msg = "[" + who + "] " + format;
		if (CompanionConfig.get().verboseAiLogging) {
			MCFriends.LOG.info(msg, args);
		} else if (MCFriends.LOG.isDebugEnabled()) {
			MCFriends.LOG.debug(msg, args);
		}
	}
}
