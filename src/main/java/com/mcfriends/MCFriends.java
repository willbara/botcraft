package com.mcfriends;

import com.mcfriends.chat.ChatRouter;
import com.mcfriends.command.CompanionCommand;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
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
		ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(CompanionManager::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(CompanionManager::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(CompanionManager::onEndTick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> ChatRouter.onChat(sender, message.getSignedContent()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CompanionCommand.register(dispatcher));
		LOG.info("MCFriends initialized (max companions {}, chat {}, griefing {})",
			CompanionConfig.get().maxCompanions, CompanionConfig.get().chatEnabled, CompanionConfig.get().griefing);
	}
}
