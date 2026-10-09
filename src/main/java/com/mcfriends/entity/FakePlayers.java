package com.mcfriends.entity;

import com.mcfriends.MCFriends;
import com.mcfriends.config.CompanionConfig;
import net.minecraft.world.GameMode;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;

/** Low-level join/leave of companion players. Must be called on the server thread. */
public final class FakePlayers {
	private FakePlayers() {
	}

	public static boolean hasSavedData(MinecraftServer server, CompanionData data) {
		return Files.exists(server.getSavePath(WorldSavePath.PLAYERDATA).resolve(data.uuid + ".dat"));
	}

	/**
	 * Creates the player and runs the vanilla join sequence (PlayerManager#onPlayerConnect),
	 * which loads playerdata (position, inventory, health, ...), adds the player to the world and
	 * tab list and broadcasts "joined the game".
	 *
	 * @param world   where to put the player; null keeps the saved location (or world spawn)
	 * @param pos     position to teleport to after joining; null keeps the saved location
	 */
	public static CompanionPlayer connect(MinecraftServer server, CompanionData data, @Nullable ServerWorld world, @Nullable Vec3d pos, float yaw) {
		GameProfile profile = new GameProfile(data.uuid, data.name);
		if (data.skinValue != null && data.skinSignature != null) {
			profile.getProperties().put("textures", new Property("textures", data.skinValue, data.skinSignature));
		}
		ServerWorld initialWorld = world != null ? world : server.getOverworld();
		healSavedData(server, data.uuid);
		CompanionPlayer player = new CompanionPlayer(server, initialWorld, profile);
		if (pos != null) {
			// Used when there is no saved data; onPlayerConnect overrides it with saved data otherwise.
			player.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0.0F);
		}
		FakeClientConnection connection = new FakeClientConnection();
		player.setFakeConnection(connection);
		server.getPlayerManager().onPlayerConnect(connection, player);
		if (CompanionConfig.get().forceSurvival && player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
			player.changeGameMode(GameMode.SURVIVAL);
		}
		if (pos != null && world != null) {
			player.teleport(world, pos.x, pos.y, pos.z, yaw, 0.0F);
		}
		MCFriends.LOG.info("Companion {} joined at {} in {}", data.name, player.getBlockPos().toShortString(), player.getWorld().getRegistryKey().getValue());
		return player;
	}

	/**
	 * Playerdata saved while dead (Health 0, from an older version or a crash) would make the
	 * companion join as a corpse: clients start its death animation and never stop it, even
	 * after we heal it. Fix the saved data before it's loaded.
	 */
	private static void healSavedData(MinecraftServer server, java.util.UUID uuid) {
		java.io.File file = server.getSavePath(net.minecraft.util.WorldSavePath.PLAYERDATA).resolve(uuid + ".dat").toFile();
		if (!file.isFile()) {
			return;
		}
		try {
			net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompressed(file);
			if (!nbt.contains("Health") || nbt.getFloat("Health") > 0) {
				return;
			}
			nbt.putFloat("Health", 20.0F);
			nbt.putShort("DeathTime", (short) 0);
			nbt.putShort("HurtTime", (short) 0);
			nbt.putShort("Fire", (short) -20);
			nbt.putShort("Air", (short) 300);
			nbt.remove("active_effects");
			nbt.remove("ActiveEffects");
			nbt.putInt("foodLevel", 20);
			nbt.putFloat("foodSaturationLevel", 5.0F);
			net.minecraft.nbt.NbtIo.writeCompressed(nbt, file);
			MCFriends.LOG.info("Companion {} was saved dead; revived its saved data", uuid);
		} catch (java.io.IOException e) {
			MCFriends.LOG.warn("Could not check saved data for companion {}", uuid, e);
		}
	}

	/** Vanilla leave sequence: saves playerdata, removes from world and tab list. */
	public static void disconnect(CompanionPlayer player, String reason) {
		if (player.isDisconnected()) {
			return;
		}
		// The vanilla path passes the reason through a packet callback we drop, so set it first.
		player.fakeConnection().disconnect(Text.literal(reason));
		player.networkHandler.disconnect(Text.literal(reason));
	}
}
