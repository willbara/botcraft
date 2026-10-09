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
