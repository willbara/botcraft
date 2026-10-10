package com.botcraft.util;

import com.botcraft.Botcraft;
import com.botcraft.config.CompanionConfig;

/** AI decision logging: DEBUG normally, INFO when verboseAiLogging is on. */
public final class AiLog {
	private AiLog() {
	}

	public static void log(String who, String format, Object... args) {
		String msg = "[" + who + "] " + format;
		if (CompanionConfig.get().verboseAiLogging) {
			Botcraft.LOG.info(msg, args);
		} else if (Botcraft.LOG.isDebugEnabled()) {
			Botcraft.LOG.debug(msg, args);
		}
	}
}
