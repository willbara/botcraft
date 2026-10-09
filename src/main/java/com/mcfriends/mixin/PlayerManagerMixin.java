package com.mcfriends.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Companions don't take up player slots: when a real player joins, the "server is full" check
 * only counts real players. Without this, a singleplayer world (8 slots) with 8 saved companions
 * would turn its own host away.
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
	@WrapOperation(method = "checkCanJoin", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
	private int mcfriends$countRealPlayers(List<?> players, Operation<Integer> original) {
		int companions = 0;
		for (Object p : players) {
			if (p instanceof CompanionPlayer) {
				companions++;
			}
		}
		return original.call(players) - companions;
	}
}
