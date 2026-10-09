package com.mcfriends.entity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mcfriends.MCFriends;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Reads/writes &lt;world&gt;/mcfriends/companions.json. */
public final class CompanionStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private CompanionStore() {
	}

	private static Path file(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("mcfriends").resolve("companions.json");
	}

	public static List<CompanionData> load(MinecraftServer server) {
		Path f = file(server);
		if (!Files.exists(f)) {
			return new ArrayList<>();
		}
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			List<CompanionData> list = GSON.fromJson(r, new TypeToken<List<CompanionData>>() {
			}.getType());
			if (list == null) {
				return new ArrayList<>();
			}
			list.removeIf(d -> d == null || d.name == null || d.uuid == null);
			return list;
		} catch (IOException | JsonParseException e) {
			MCFriends.LOG.error("Could not read {}", f, e);
			return new ArrayList<>();
		}
	}

	public static void save(MinecraftServer server, List<CompanionData> companions) {
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("companions.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(companions, w);
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			MCFriends.LOG.error("Could not write {}", f, e);
		}
	}
}
