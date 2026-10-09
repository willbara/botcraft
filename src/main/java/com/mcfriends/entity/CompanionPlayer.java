package com.mcfriends.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Server-side player driven by the companion AI instead of a client.
 *
 * <p>For real players, ServerPlayNetworkHandler#tick calls {@link #playerTick()} (which runs
 * LivingEntity movement/physics, hunger, item use). Our handler is never ticked because the fake
 * connection is not registered with the network IO, so we call it from {@link #tick()}.
 */
public class CompanionPlayer extends ServerPlayerEntity {
	public CompanionPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
		super(server, world, profile);
	}

	@Override
	public void tick() {
		// Real players update chunk tracking from movement packets; do it periodically so
		// the companion keeps chunks around itself loaded and tracked.
		if (this.age % 10 == 0) {
			this.getServerWorld().getChunkManager().updatePosition(this);
		}
		super.tick();
		// Server-side collision treats unloaded chunks as empty, so a companion restored on a
		// fresh server would fall through the ground before its chunks finish loading.
		if (!this.isRegionUnloaded()) {
			double x = this.getX();
			double y = this.getY();
			double z = this.getZ();
			this.playerTick();
			// Walking/sprinting/swimming exhaustion and stats normally come from the client's
			// movement packets (ServerPlayNetworkHandler#onPlayerMove); apply them for our own movement.
			if (!this.hasVehicle()) {
				this.increaseTravelMotionStats(this.getX() - x, this.getY() - y, this.getZ() - z);
			}
		}
	}

	/**
	 * ServerPlayerEntity#fall is a no-op because fall damage normally comes from the client's
	 * movement packets (handleFall). Route server-side movement through handleFall so falls hurt.
	 */
	@Override
	protected void fall(double heightDifference, boolean onGround, BlockState state, BlockPos landedPosition) {
		this.handleFall(0.0, heightDifference, 0.0, onGround);
	}
}
