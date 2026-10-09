package com.mcfriends;

import com.mcfriends.chat.ChatRouter;
import com.mcfriends.command.CompanionCommand;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mcfriends.config.CompanionConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCFriends implements ModInitializer {
	public static final String MOD_ID = "mcfriends";
	public static final Logger LOG = LoggerFactory.getLogger("MCFriends");

	@Override
	public void onInitialize() {
		CompanionConfig.load();
		try {
			int n = com.mcfriends.chat.Templates.loadOverrides(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcfriends-dialogue.json"));
			if (n >= 0) {
				LOG.info("Loaded {} dialogue override(s) from mcfriends-dialogue.json", n);
			}
		} catch (Exception e) {
			LOG.error("Could not read config/mcfriends-dialogue.json", e);
		}
		ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(CompanionManager::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(CompanionManager::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(CompanionManager::onEndTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> CompanionManager.onPlayerJoin(handler.player));
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> ChatRouter.onChat(sender, message.getSignedContent()));
		// Drops from a companion's own work, so it can make sure it picks them up.
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
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
				}
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CompanionCommand.register(dispatcher));
		LOG.info("MCFriends initialized (max companions {}, chat {}, griefing {})",
			CompanionConfig.get().maxCompanions == 0 ? "unlimited" : CompanionConfig.get().maxCompanions, CompanionConfig.get().chatEnabled, CompanionConfig.get().griefing);
	}
}
