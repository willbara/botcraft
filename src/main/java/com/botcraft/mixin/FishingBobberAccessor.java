package com.botcraft.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether a fish is on the hook right now (the moment a player sees the bobber dip and reels in).
 * Companions fishing read it instead of watching for the splash.
 */
@Mixin(FishingBobberEntity.class)
public interface FishingBobberAccessor {
	@Accessor("hookCountdown")
	int botcraft$hookCountdown();
}
