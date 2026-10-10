package com.mcfriends.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Awake companions don't hold up the night: "everyone must sleep" counts real players and the
 * companions that are in bed. A companion standing guard (or with no bed) no longer stops the
 * night being skipped when its player sleeps, and a world of only companions skips the night
 * once they're all in bed.
 */
@Mixin(SleepManager.class)
public abstract class SleepManagerMixin {
	@WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;isSpectator()Z"))
	private boolean mcfriends$skipAwakeCompanions(ServerPlayerEntity player, Operation<Boolean> original) {
		if (player instanceof CompanionPlayer && !player.isSleeping()) {
			return true; // left out of the count, like a spectator
		}
		return original.call(player);
	}
}
