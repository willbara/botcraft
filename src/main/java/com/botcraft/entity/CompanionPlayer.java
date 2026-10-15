package com.botcraft.entity;

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
	private FakeClientConnection fakeConnection;

	/** Every skin layer: cape, jacket, both sleeves, both trouser legs and the hat (bits 0-6). */
	private static final byte ALL_SKIN_PARTS = 0x7F;

	public CompanionPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
		super(server, world, profile);
		// A real client says which skin layers to show (its skin customisation settings); a
		// companion never does, which left it with only the base layer: no hats, jackets or
		// sleeves, and no cape. Show them all, as a player with default settings would.
		this.getDataTracker().set(PLAYER_MODEL_PARTS, ALL_SKIN_PARTS);
	}

	void setFakeConnection(FakeClientConnection connection) {
		this.fakeConnection = connection;
	}

	FakeClientConnection fakeConnection() {
		return fakeConnection;
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

	/** Damage that got through: the companion feels it, and remembers who did it (see faction.Incidents). */
	@Override
	public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
		var m = com.botcraft.CompanionManager.get();
		var self = m == null ? null : m.byPlayer(this);
		if (self != null && com.botcraft.brain.EasterEggs.unkillable(self)) {
			// Hausemaster can't be hurt, but it notices who tried (and fights popbob back, see Hostility).
			if (source.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity attacker && attacker != this) {
				self.relations().note(attacker.getGameProfile().getName(), com.botcraft.brain.Relationship.Event.HIT);
				self.alarm();
			}
			if (source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD)) {
				com.botcraft.brain.EasterEggs.vanish(self, null, 4, 24); // fell out of the world: back on solid ground
			}
			return false;
		}
		boolean hurt = super.damage(source, amount);
		if (hurt && self != null && com.botcraft.brain.EasterEggs.is(self, com.botcraft.brain.EasterEggs.Kind.HEROBRINE)
			&& source.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity attacker && isAlive()) {
			com.botcraft.brain.EasterEggs.vanish(self, attacker.getPos(), 60, 110); // hit Herobrine and he's gone
			self.brain().clearAll();
		}
		if (hurt && amount > 0) {
			try {
				com.botcraft.faction.Incidents.companionHurt(this, source, amount);
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error handling damage to companion {}", getGameProfile().getName(), e);
			}
		}
		return hurt;
	}

	/** Swim downward, like holding sneak in water (vanilla does this on the client only). */
	public void swimDown() {
		if (isTouchingWater() && shouldSwimInFluids()) {
			knockDownwards();
		}
	}
}
