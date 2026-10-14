package com.botcraft;

import com.botcraft.chat.ChatRouter;
import com.botcraft.command.CompanionCommand;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.botcraft.config.CompanionConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Botcraft implements ModInitializer {
	public static final String MOD_ID = "botcraft";
	public static final Logger LOG = LoggerFactory.getLogger("Botcraft");

	@Override
	public void onInitialize() {
		LegacyNames.migrateConfig();
		CompanionConfig.load();
		try {
			int n = com.botcraft.chat.Templates.loadOverrides(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("botcraft-dialogue.json"));
			if (n >= 0) {
				LOG.info("Loaded {} dialogue override(s) from botcraft-dialogue.json", n);
			}
		} catch (Exception e) {
			LOG.error("Could not read config/botcraft-dialogue.json", e);
		}
		ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(CompanionManager::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(CompanionManager::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(CompanionManager::onEndTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> CompanionManager.onPlayerJoin(handler.player));
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> ChatRouter.onChat(sender, message.getSignedContent()));
		// Whatever was there is gone: drop it from the built and litter records. A companion
		// breaking the ground at home leaves a hole to fill in later (see colony/Upkeep).
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof net.minecraft.server.world.ServerWorld sw && player instanceof com.botcraft.entity.CompanionPlayer) {
				com.botcraft.colony.Upkeep.breaking(sw, pos, state);
			}
			// A player taking apart what a faction built at its base (the record is gone after the break).
			if (world instanceof net.minecraft.server.world.ServerWorld sw && player instanceof net.minecraft.server.network.ServerPlayerEntity sp
				&& !(player instanceof com.botcraft.entity.CompanionPlayer) && com.botcraft.colony.Builds.recorded(sw, pos)) {
				com.botcraft.faction.Incidents.buildBroken(sp, sw, pos);
			}
			return true;
		});
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof net.minecraft.server.world.ServerWorld sw) {
				com.botcraft.colony.Builds.forget(sw, pos);
				for (com.botcraft.colony.Colony colony : com.botcraft.colony.Colony.all()) {
					colony.forgetPlaced(sw.getRegistryKey().getValue().toString(), pos);
				}
			}
			// Drops from a companion's own work, so it can make sure it picks them up.
			CompanionManager m = CompanionManager.get();
			Companion c = m == null ? null : m.byPlayer((net.minecraft.server.network.ServerPlayerEntity) player);
			if (c != null) {
				c.leftovers().expect(pos);
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			CompanionManager m = CompanionManager.get();
			if (m != null && source.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity killer && !(entity instanceof net.minecraft.entity.player.PlayerEntity)) {
				Companion c = m.byPlayer(killer);
				if (c != null) {
					c.leftovers().expect(entity.getBlockPos());
				} else {
					com.botcraft.faction.Incidents.mobKilled(killer, entity); // a player saving one of us
				}
			}
		});
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(com.botcraft.entity.InventoryView::onUseEntity);
		// Players helping themselves to a faction's chests.
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient() && hand == net.minecraft.util.Hand.MAIN_HAND && world instanceof net.minecraft.server.world.ServerWorld sw
				&& player instanceof net.minecraft.server.network.ServerPlayerEntity sp && !player.isSpectator()) {
				try {
					com.botcraft.faction.Incidents.chestOpened(sp, sw, hit.getBlockPos());
				} catch (RuntimeException e) {
					LOG.error("Error checking a chest opening", e);
				}
			}
			return net.minecraft.util.ActionResult.PASS;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CompanionCommand.register(dispatcher));
		LOG.info("Botcraft initialized (max companions {}, chat {}, griefing {})",
			CompanionConfig.get().maxCompanions == 0 ? "unlimited" : CompanionConfig.get().maxCompanions, CompanionConfig.get().chatEnabled, CompanionConfig.get().griefing);
	}
}
