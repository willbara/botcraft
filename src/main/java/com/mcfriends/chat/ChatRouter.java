package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.action.Inventories;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes player chat to companions. A message is for a companion if it names it ("Bob, follow
 * me"), addresses everyone ("everyone stop"), or - without a name - if the sender owns exactly
 * one companion within 32 blocks and the message is a clear order. Orders from anyone but
 * the owner are politely refused.
 */
public final class ChatRouter {
	private static final double IMPLICIT_RANGE = 32.0;

	private ChatRouter() {
	}

	/** Called from ServerMessageEvents.CHAT_MESSAGE (server thread). */
	public static void onChat(ServerPlayerEntity sender, String message) {
		CompanionManager m = CompanionManager.get();
		if (m == null || sender instanceof CompanionPlayer) {
			return;
		}
		List<String> names = new ArrayList<>();
		for (Companion c : m.all()) {
			names.add(c.name());
		}
		IntentParser.Parsed parsed = IntentParser.parse(message, names);
		Intent intent = parsed.intent();
		List<Companion> targets = new ArrayList<>();
		if (parsed.addressed().contains(IntentParser.ALL)) {
			for (Companion c : m.all()) {
				if (sender.getUuid().equals(c.data.owner) || c.data.owner == null) {
					targets.add(c);
				}
			}
		} else if (!parsed.addressed().isEmpty()) {
			for (String n : parsed.addressed()) {
				Companion c = m.byName(n);
				if (c != null) {
					targets.add(c);
				}
			}
		} else if (intent.type().isOrder() || intent.type() == Intent.Type.STATUS) {
			List<Companion> mine = new ArrayList<>();
			for (Companion c : m.ownedBy(sender)) {
				CompanionPlayer p = c.player();
				if (p != null && p.getWorld() == sender.getWorld() && p.distanceTo(sender) < IMPLICIT_RANGE) {
					mine.add(c);
				}
			}
			if (mine.size() == 1) {
				targets.addAll(mine);
			}
		}
		for (Companion c : targets) {
			if (c.isOnline()) {
				AiLog.log(c.name(), "heard {} from {}: '{}'", intent, sender.getGameProfile().getName(), message);
				c.hear(sender, intent);
			}
		}
	}

	/** Carry out what was asked and reply. Runs after the companion's reaction delay. */
	public static void respond(Companion c, ServerPlayerEntity sender, Intent intent) {
		Speech speech = c.speech();
		Map<String, String> vars = new HashMap<>();
		boolean isOwner = c.data.owner == null || sender.getUuid().equals(c.data.owner);
		if (intent.type().isOrder() && !isOwner) {
			speech.reply("not_owner", sender, vars);
			return;
		}
		switch (intent.type()) {
			case FOLLOW -> {
				CommandTasks.follow(c, sender);
				speech.reply("ack.follow", sender, vars);
			}
			case STOP -> {
				CommandTasks.stay(c);
				speech.reply("ack.stop", sender, vars);
			}
			case COME_HERE -> {
				CommandTasks.comeHere(c, sender);
				speech.reply("ack.come", sender, vars);
			}
			case DEFEND -> {
				CommandTasks.defend(c, sender);
				speech.reply("ack.defend", sender, vars);
			}
			case GATHER -> {
				vars.put("item", intent.item());
				vars.put("count", Integer.toString(intent.count()));
				if (CommandTasks.gather(c, intent.item(), intent.count(), sender) != null) {
					speech.reply("cant.gather", sender, vars);
				} else {
					vars.put("item", "logs");
					speech.reply("ack.gather", sender, vars);
				}
			}
			case GIVE -> give(c, sender, intent, vars);
			case STATUS -> speech.reply("status", sender, vars);
			case GREETING -> speech.reply("greeting", sender, vars);
			case THANKS -> speech.reply("thanks", sender, vars);
			case UNKNOWN -> {
				if (c.data.chattiness > 0.3F) {
					speech.reply("confused", sender, vars);
				}
			}
		}
	}

	private static void give(Companion c, ServerPlayerEntity sender, Intent intent, Map<String, String> vars) {
		CompanionPlayer p = c.player();
		boolean wood = CommandTasks.isWoodWord(intent.item());
		vars.put("item", wood ? "logs" : intent.item());
		if (p.distanceTo(sender) > 6.0) {
			c.speech().reply("give.far", sender, vars);
			CommandTasks.comeHere(c, sender);
			return;
		}
		int given = wood
			? c.actions().giveItems(sender, Inventories::isLog, intent.count())
			: c.actions().giveItems(sender, s -> s.getItem().toString().contains(intent.item()), intent.count());
		c.perception().invalidateInventory();
		vars.put("count", Integer.toString(given));
		c.speech().reply(given > 0 ? "ack.give" : "give.none", sender, vars);
	}
}
