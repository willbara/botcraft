package com.botcraft.mixin;

import com.botcraft.colony.Builds;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers who put blocks down (see {@link Builds}): blocks a player places are built, and so
 * are base blocks a companion places (chests, furnaces, beds...). A companion's throwaway blocks
 * (bridges, pillars) are litter instead, so they can be cleared up again.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("RETURN"))
	private void botcraft$recordPlacement(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
		if (!cir.getReturnValue().isAccepted() || !(context.getWorld() instanceof ServerWorld world) || context.getPlayer() == null) {
			return;
		}
		BlockPos pos = context.getBlockPos();
		if (world.getBlockState(pos).isAir()) {
			return;
		}
		ItemStack what = new ItemStack(((BlockItem) (Object) this).getBlock().asItem());
		if (context.getPlayer() instanceof CompanionPlayer && com.botcraft.action.Inventories.isThrowaway(what)) {
			com.botcraft.nav.BreakPolicy.recordPlaced(world, pos);
		} else {
			Builds.record(world, pos);
		}
	}
}
