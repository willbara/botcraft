package com.botcraft.mixin;

import com.botcraft.brain.EasterEggs;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hostile mobs leave Herobrine alone (easter egg): none of them will pick him as a target, and
 * one already after him loses interest (target goals re-check this every tick).
 */
@Mixin(LivingEntity.class)
public abstract class MobTargetMixin {
	@Inject(method = "canTarget(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
	private void botcraft$spareHerobrine(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (target instanceof CompanionPlayer cp && (Object) this instanceof Monster
			&& EasterEggs.of(cp.getGameProfile().getName()) == EasterEggs.Kind.HEROBRINE) {
			cir.setReturnValue(false);
		}
	}
}
