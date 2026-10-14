package com.botcraft.chat;

import com.botcraft.Botcraft;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Saves the words players taught ({@link Lexicon}) in {@code <world>/botcraft/lexicon.json}. */
public final class LexiconStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private LexiconStore() {
	}

	private static Path file(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("botcraft").resolve("lexicon.json");
	}

	public static void load(MinecraftServer server) {
		Lexicon.clear();
		Path f = file(server);
		if (!Files.exists(f)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			Map<String, String> words = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, String>>() {
			}.getType());
			Lexicon.load(words);
		} catch (IOException | JsonParseException e) {
			Botcraft.LOG.error("Could not read {}", f, e);
		}
	}

	public static void save(MinecraftServer server) {
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("lexicon.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(new LinkedHashMap<>(Lexicon.all()), w);
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Botcraft.LOG.error("Could not write {}", f, e);
		}
	}
}
