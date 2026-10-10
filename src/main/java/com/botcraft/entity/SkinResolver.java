package com.botcraft.entity;

import com.botcraft.Botcraft;
import com.botcraft.config.CompanionConfig;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Turns a skin argument into something a companion can wear:
 * <ol>
 *   <li>a built-in default skin ("steve", "kai_slim", ...): fully offline;</li>
 *   <li>an online player's name: copies their signed textures, offline;</li>
 *   <li>any registered Minecraft account name: looked up from Mojang the same way player
 *       heads are (profile repository + session service), on a worker thread. The signed
 *       textures are then stored with the companion so restarts never need the network.</li>
 * </ol>
 * The callback always runs on the server thread.
 */
public final class SkinResolver {
	private static final long CACHE_MILLIS = 30 * 60 * 1000L;

	private record Cached(Optional<Property> textures, long at) {
	}

	private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

	private SkinResolver() {
	}

	/** What to wear. {@code defaultIndex} is used only when there are no textures (-1 = whatever the UUID gives). */
	public record SkinChoice(String id, int defaultIndex, @Nullable String value, @Nullable String signature) {
		public static SkinChoice random() {
			return new SkinChoice("random", -1, null, null);
		}

		public boolean hasTextures() {
			return value != null && signature != null;
		}
	}

	/** Result for the user: the skin plus an optional note (e.g. lookup failed, used a default). */
	public record Result(SkinChoice choice, @Nullable String note) {
	}

	/**
	 * @param skin         argument from the command, or null
	 * @param companionName used for the lookup when skin is null and autoSkinFromName is on
	 */
	public static void resolve(MinecraftServer server, @Nullable String skin, String companionName, Consumer<Result> callback) {
		if (skin == null) {
			if (CompanionConfig.get().autoSkinFromName && CompanionConfig.get().allowSkinLookup) {
				lookupAccount(server, companionName, true, callback);
			} else {
				callback.accept(new Result(SkinChoice.random(), null));
			}
			return;
		}
		int index = SkinUtil.skinIndex(skin);
		if (index >= 0) {
			callback.accept(new Result(new SkinChoice(skin.toLowerCase(Locale.ROOT), index, null, null), null));
			return;
		}
		ServerPlayerEntity online = server.getPlayerManager().getPlayer(skin);
		if (online != null) {
			Property p = signedTextures(online.getGameProfile());
			if (p != null) {
				callback.accept(new Result(new SkinChoice(online.getGameProfile().getName(), -1, p.getValue(), p.getSignature()), null));
				return;
			}
		}
		if (!CompanionConfig.get().allowSkinLookup) {
			callback.accept(new Result(SkinChoice.random(), "Skin lookups are disabled (allowSkinLookup); using a default skin"));
			return;
		}
		lookupAccount(server, skin, false, callback);
	}

	private static void lookupAccount(MinecraftServer server, String name, boolean quiet, Consumer<Result> callback) {
		String key = name.toLowerCase(Locale.ROOT);
		Cached cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
			callback.accept(toResult(name, cached.textures(), quiet));
			return;
		}
		CompletableFuture.supplyAsync(() -> fetch(server, name), Util.getMainWorkerExecutor())
			.exceptionally(e -> {
				Botcraft.LOG.warn("Skin lookup for {} failed: {}", name, e.toString());
				return Optional.empty();
			})
			.thenAcceptAsync(textures -> {
				CACHE.put(key, new Cached(textures, System.currentTimeMillis()));
				callback.accept(toResult(name, textures, quiet));
			}, server);
	}

	private static Result toResult(String name, Optional<Property> textures, boolean quiet) {
		if (textures.isPresent()) {
			return new Result(new SkinChoice(name, -1, textures.get().getValue(), textures.get().getSignature()), null);
		}
		return new Result(SkinChoice.random(), quiet ? null : "Couldn't find a Minecraft account named " + name + " (or no network); using a default skin");
	}

	/** Blocking: runs on a worker thread. */
	private static Optional<Property> fetch(MinecraftServer server, String name) {
		AtomicReference<GameProfile> found = new AtomicReference<>();
		server.getGameProfileRepo().findProfilesByNames(new String[]{name}, Agent.MINECRAFT, new ProfileLookupCallback() {
			@Override
			public void onProfileLookupSucceeded(GameProfile profile) {
				found.set(profile);
			}

			@Override
			public void onProfileLookupFailed(GameProfile profile, Exception exception) {
				Botcraft.LOG.info("No Minecraft account named {} ({})", name, exception.getMessage());
			}
		});
		if (found.get() == null) {
			return Optional.empty();
		}
		// requireSecure=true asks the session server for signed properties, which clients require.
		GameProfile filled = server.getSessionService().fillProfileProperties(found.get(), true);
		Property p = signedTextures(filled);
		Botcraft.LOG.info("Looked up skin for {} ({}): {}", name, found.get().getId(), p != null ? "ok" : "no textures");
		return Optional.ofNullable(p);
	}

	@Nullable
	private static Property signedTextures(GameProfile profile) {
		for (Property p : profile.getProperties().get("textures")) {
			if (p.hasSignature()) {
				return p;
			}
		}
		return null;
	}
}
