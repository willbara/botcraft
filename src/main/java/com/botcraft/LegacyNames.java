package com.botcraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod used to be called MCFriends. Files saved under the old name are moved to the new one
 * the first time Botcraft runs, so existing worlds keep their companions, colony and memories:
 * {@code config/mcfriends.json}, {@code config/mcfriends-dialogue.json} and {@code <world>/mcfriends/}.
 */
final class LegacyNames {
	private static final String OLD = "mcfriends";

	private LegacyNames() {
	}

	/** Before the config is read. */
	static void migrateConfig() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		move(dir.resolve(OLD + ".json"), dir.resolve(Botcraft.MOD_ID + ".json"));
		move(dir.resolve(OLD + "-dialogue.json"), dir.resolve(Botcraft.MOD_ID + "-dialogue.json"));
	}

	/** Before anything is loaded from the world. */
	static void migrateWorld(MinecraftServer server) {
		Path root = server.getSavePath(WorldSavePath.ROOT);
		move(root.resolve(OLD), root.resolve(Botcraft.MOD_ID));
	}

	private static void move(Path from, Path to) {
		if (!Files.exists(from) || Files.exists(to)) {
			return;
		}
		try {
			Files.move(from, to);
			Botcraft.LOG.info("Moved {} to {} (the mod was renamed from MCFriends)", from, to);
		} catch (IOException e) {
			Botcraft.LOG.error("Could not move {} to {}", from, to, e);
		}
	}
}
