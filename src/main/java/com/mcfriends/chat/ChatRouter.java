package com.mcfriends.chat;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Jobs;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.CraftPlanner;
import com.mcfriends.knowledge.ItemNames;
import com.mcfriends.knowledge.OreKind;
import com.mcfriends.memory.Memory;
import com.mcfriends.util.AiLog;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routes player chat to companions and carries out what was asked.
 *
 * <p>A message is only for a companion if it says its name ("Bob, follow me") or addresses
 * everyone ("everyone stop" - only your own companions). Companions never pick up un-addressed
 * chat, so orders meant for one don't interrupt another. Orders from anyone but the owner are
 * politely refused; questions anyone may ask.
 *
 * <p>Several clauses become a plan: "get logs and then mine for coal" queues two jobs.
 * "also ..." / "after that ..." appends to the current plan instead of replacing it.
 */
public final class ChatRouter {
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
		List<Companion> targets = new ArrayList<>();
		if (parsed.addressed().contains(IntentParser.ALL)) {
			for (Companion c : m.all()) {
				if (sender.getUuid().equals(c.data.owner)) {
					targets.add(c);
				}
			}
		} else {
			for (String n : parsed.addressed()) {
				Companion c = m.byName(n);
				if (c != null) {
					targets.add(c);
				}
			}
		}
		boolean unknown = parsed.intents().size() == 1 && parsed.intent().type() == Intent.Type.UNKNOWN;
		for (Companion c : targets) {
			if (!c.isOnline() || (unknown && !parsed.direct())) {
				continue; // only mentioned in passing
			}
			AiLog.log(c.name(), "heard {} (append={}) from {}: '{}'", parsed.intents(), parsed.append(), sender.getGameProfile().getName(), message);
			c.hear(sender, parsed.intents(), parsed.append());
		}
	}

	/**
	 * Carry out the request and reply. {@code sender} is null for console commands.
	 *
	 * @return an error for command feedback, or null
	 */
	@Nullable
	public static String respond(Companion c, @Nullable ServerPlayerEntity sender, List<Intent> intents, boolean append) {
		Speech speech = c.speech();
		boolean isOwner = sender == null || c.data.owner == null || sender.getUuid().equals(c.data.owner);
		List<String> jobs = new ArrayList<>();
		String mode = null;
		boolean defend = false;
		String error = null;

		for (Intent intent : intents) {
			Map<String, String> vars = new HashMap<>();
			if (intent.type().isOrder() && !isOwner) {
				speech.reply("not_owner", sender, vars);
				return "not the owner";
			}
			switch (intent.type()) {
				case FOLLOW -> mode = "follow";
				case FREE -> {
					mode = "free";
					if (c.memory().place("home") == null) {
						BlockPos at = c.player().getBlockPos();
						c.memory().setPlace("home", at, c.dimension(), c.server.getOverworld().getTime());
					}
				}
				case STOP -> mode = "stay";
				case DEFEND -> {
					mode = "follow";
					defend = true;
				}
				case CANCEL -> {
					c.jobs().clear();
					c.brain().reconsider();
					speech.reply("ack.cancel", sender, vars);
				}
				case COME_HERE -> {
					if (sender == null) {
						error = "Only a player can call a companion over";
					} else {
						BlockPos at = sender.getBlockPos();
						jobs.add("goto " + at.getX() + " " + at.getY() + " " + at.getZ() + " over to you");
					}
				}
				case HOME_GO -> {
					if (c.memory().place("home") == null) {
						speech.reply("no_home", sender, vars);
					} else {
						jobs.add("home");
					}
				}
				case HOME_SET -> {
					BlockPos at = sender != null ? sender.getBlockPos() : c.player().getBlockPos();
					String dim = sender != null ? sender.getWorld().getRegistryKey().getValue().toString() : c.dimension();
					c.memory().setPlace("home", at, dim, c.server.getOverworld().getTime());
					vars.put("x", Integer.toString(at.getX()));
					vars.put("y", Integer.toString(at.getY()));
					vars.put("z", Integer.toString(at.getZ()));
					speech.reply("ack.home_set", sender, vars);
				}
				case SLEEP -> {
					if (c.perception().isNight()) {
						jobs.add("sleep");
					} else {
						speech.reply("not_night", sender, vars);
					}
				}
				case GATHER, MINE, CRAFT, BRING -> {
					List<String> specs = new ArrayList<>();
					String problem = acquire(c, intent, specs);
					if (problem != null) {
						vars.put("item", intent.item().isEmpty() ? "that" : intent.item());
						vars.put("reason", problem);
						speech.reply("cant.do", sender, vars);
						error = problem;
					} else {
						jobs.addAll(specs);
					}
				}
				case HUNT -> {
					String what = intent.item();
					int n = intent.count() < 0 ? 3 : intent.count();
					if (what.equals("food")) {
						jobs.add("hunt meat " + n);
						jobs.add("smelt food " + n);
					} else if (com.mcfriends.knowledge.Animals.byWord(what) == null) {
						vars.put("reason", "I don't know how to hunt " + what);
						speech.reply("cant.do", sender, vars);
						error = vars.get("reason");
					} else {
						jobs.add("hunt " + what.split(" ")[0] + " " + n);
					}
				}
				case HARVEST -> {
					// "this farm": around whoever asked; otherwise the farm we remember, otherwise here.
					BlockPos at = sender != null && sender.getWorld() == c.player().getWorld() && sender.distanceTo(c.player()) < 48
						? sender.getBlockPos() : null;
					Memory.Place farm = c.memory().place("farm");
					if (at == null && farm != null) {
						at = farm.pos();
					}
					if (at == null) {
						at = c.player().getBlockPos();
					}
					jobs.add("harvest " + at.getX() + " " + at.getY() + " " + at.getZ() + " 10");
				}
				case FARM -> {
					BlockPos at = sender != null ? sender.getBlockPos() : c.player().getBlockPos();
					int size = intent.count() < 0 ? 4 : Math.min(intent.count(), 9);
					String seedId = ItemNames.id(com.mcfriends.knowledge.Crops.seedFor(intent.item()));
					jobs.add("farm " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + size + " " + seedId);
				}
				case SMELT -> {
					String spec = smeltSpec(c, intent);
					if (spec == null) {
						vars.put("reason", "I don't know how to smelt " + (intent.item().isEmpty() ? "that" : intent.item()));
						speech.reply("cant.do", sender, vars);
						error = vars.get("reason");
					} else {
						jobs.add(spec);
					}
				}
				case GIVE -> {
					String what = giveWhat(intent.item());
					if (what == null) {
						vars.put("item", intent.item());
						speech.reply("give.none", sender, vars);
					} else if (sender != null && c.player().distanceTo(sender) <= 5.0 && c.player().canSee(sender)) {
						give(c, sender, what, intent.count() < 0 ? 64 : intent.count(), vars);
					} else {
						jobs.add("deliver " + what + " " + (intent.count() < 0 ? 64 : intent.count()));
					}
				}
				case WHERE -> where(c, sender, vars);
				case INVENTORY -> inventory(c, sender, vars);
				case STATS -> stats(c, sender, vars);
				case FOUND -> found(c, sender, intent.item(), vars);
				case HELP -> speech.reply("help", sender, vars);
				case STATUS -> speech.reply("status", sender, vars);
				case GREETING -> {
					Memory.Person p = sender == null ? null : c.memory().knownPerson(sender.getGameProfile().getName());
					speech.reply(p != null && p.orders > 3 ? "greeting.friend" : "greeting", sender, vars);
				}
				case THANKS -> {
					if (sender != null) {
						c.memory().person(sender.getGameProfile().getName(), c.server.getOverworld().getTime()).thanks++;
					}
					speech.reply("thanks", sender, vars);
				}
				case UNKNOWN -> {
					if (sender == null) {
						error = "I don't understand that order";
					} else if (c.data.chattiness > 0.3F) {
						speech.reply("confused", sender, vars);
					}
				}
			}
		}

		if (sender != null && (mode != null || !jobs.isEmpty())) {
			CommandTasks.claimOwner(c, sender);
			c.memory().person(sender.getGameProfile().getName(), c.server.getOverworld().getTime()).orders++;
		}
		if (mode != null) {
			c.data.mode = mode;
			c.data.defendOwner = defend;
			if (jobs.isEmpty()) {
				c.jobs().clear(); // a new standing order replaces whatever we were doing
			}
			if ("stay".equals(mode)) {
				c.brain().clearAll();
			}
			c.brain().reconsider();
			Map<String, String> vars = new HashMap<>();
			if (jobs.isEmpty()) {
				speech.reply(defend ? "ack.defend" : "stay".equals(mode) ? "ack.stop" : "free".equals(mode) ? "ack.free" : "ack.follow", sender, vars);
			}
		}
		if (!jobs.isEmpty()) {
			if (append) {
				c.jobs().append(jobs);
			} else {
				c.jobs().replace(jobs);
			}
			Map<String, String> vars = new HashMap<>();
			vars.put("plan", jobs.stream().map(Jobs::describe).collect(Collectors.joining(", then ")));
			speech.reply(append ? "ack.plan_more" : "ack.plan", sender, vars);
		}
		return error;
	}

	/**
	 * Turn "get/mine/craft/bring X" into job specs: wood -> chop, ores/stone -> mine, anything
	 * craftable -> craft (the craft job plans its own prerequisites). Bring adds a delivery.
	 *
	 * @return a reason we can't, or null
	 */
	@Nullable
	static String acquire(Companion c, Intent intent, List<String> out) {
		String phrase = intent.item().trim();
		int n = intent.count();
		if (phrase.isEmpty()) {
			phrase = switch (intent.type()) {
				case MINE -> "coal";
				case CRAFT -> "";
				default -> "logs";
			};
			if (phrase.isEmpty()) {
				return "you didn't say what to craft";
			}
		}
		String deliver;
		if (CommandTasks.isWoodWord(phrase)) {
			int count = n < 0 ? 16 : n;
			out.add("gather logs " + count);
			deliver = "deliver logs " + count;
		} else if (OreKind.byWord(phrase) != null) {
			OreKind k = OreKind.byWord(phrase);
			int count = n < 0 ? 8 : n;
			out.add("mine " + k.word + " " + count);
			deliver = "deliver " + k.word + " " + count;
		} else {
			Item item = ItemNames.resolve(phrase);
			if (item == null) {
				return "I don't know what " + phrase + " is";
			}
			int count = n < 0 ? 1 : n;
			String spec = Jobs.sourceSpec(c, item, count);
			if (spec == null) {
				return "I don't know how to get " + ItemNames.display(item) + " yet";
			}
			out.add(spec);
			deliver = "deliver " + ItemNames.id(item) + " " + count;
		}
		if (intent.type() == Intent.Type.BRING) {
			out.add(deliver);
		}
		return null;
	}

	/** "smelt 5 iron" -> iron ingots; "cook the beef" / "cook food" -> cook raw meat. */
	@Nullable
	static String smeltSpec(Companion c, Intent intent) {
		String phrase = intent.item().trim();
		int n = intent.count();
		if (phrase.isEmpty() || phrase.matches("(the |some |our |my )?(food|meat|dinner|lunch|it|them|everything)")) {
			return "smelt food " + (n < 0 ? 8 : n);
		}
		OreKind ore = OreKind.byWord(phrase);
		Item item;
		if (ore != null && ore != OreKind.STONE && ore != OreKind.COAL && ore.drops.size() == 1) {
			item = ItemNames.resolve(ore.word + " ingot");
		} else {
			item = ItemNames.resolve(phrase);
		}
		if (item == null) {
			return null;
		}
		var asInput = com.mcfriends.knowledge.Smelting.forInput(c.server, new ItemStack(item));
		if (asInput != null && !com.mcfriends.knowledge.Smelting.smeltable(c.server, item)) {
			item = asInput.getOutput(c.server.getRegistryManager()).getItem(); // "cook beef" -> cooked beef
		}
		if (!com.mcfriends.knowledge.Smelting.smeltable(c.server, item)) {
			return null;
		}
		return "smelt " + ItemNames.id(item) + " " + (n < 0 ? 4 : n);
	}

	/** Deliverable key for a give request, or null if it's something we can't name. */
	@Nullable
	private static String giveWhat(String phrase) {
		if (phrase.equals("all")) {
			return "all";
		}
		if (phrase.isEmpty() || CommandTasks.isWoodWord(phrase)) {
			return "logs";
		}
		OreKind k = OreKind.byWord(phrase);
		if (k != null) {
			return k.word;
		}
		Item item = ItemNames.resolve(phrase);
		return item == null ? null : ItemNames.id(item);
	}

	private static void give(Companion c, ServerPlayerEntity sender, String what, int count, Map<String, String> vars) {
		int given = c.actions().giveItems(sender, Jobs.deliverable(what), count);
		c.perception().invalidateInventory();
		vars.put("count", Integer.toString(given));
		vars.put("item", what.equals("all") ? "things" : what.replace('_', ' '));
		c.speech().reply(given > 0 ? "ack.give" : "give.none", sender, vars);
	}

	// ---- questions ------------------------------------------------------------------------------

	private static void where(Companion c, @Nullable ServerPlayerEntity sender, Map<String, String> vars) {
		CompanionPlayer p = c.player();
		BlockPos pos = p.getBlockPos();
		vars.put("x", Integer.toString(pos.getX()));
		vars.put("y", Integer.toString(pos.getY()));
		vars.put("z", Integer.toString(pos.getZ()));
		vars.put("biome", p.getWorld().getBiome(pos).getKey().map(k -> k.getValue().getPath().replace('_', ' ')).orElse("somewhere"));
		if (sender != null && sender.getWorld() == p.getWorld()) {
			double d = sender.distanceTo(p);
			vars.put("dist", Integer.toString((int) Math.round(d)));
			vars.put("dir", compass(p.getX() - sender.getX(), p.getZ() - sender.getZ()));
			c.speech().reply(d < 6 ? "where.near" : "where", sender, vars);
		} else {
			c.speech().reply("where.far", sender, vars);
		}
	}

	/** Compass direction of a horizontal offset (Minecraft: -Z is north, +X is east). */
	static String compass(double dx, double dz) {
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
		return names[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
	}

	private static void inventory(Companion c, @Nullable ServerPlayerEntity sender, Map<String, String> vars) {
		var inv = c.player().getInventory();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) {
				counts.merge(s.getName().getString().toLowerCase(), s.getCount(), Integer::sum);
			}
		}
		if (counts.isEmpty()) {
			c.speech().reply("inventory.empty", sender, vars);
			return;
		}
		List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < Math.min(6, list.size()); i++) {
			var e = list.get(i);
			parts.add(e.getValue() == 1 ? "a " + e.getKey() : e.getValue() + " " + e.getKey());
		}
		String items = String.join(", ", parts) + (list.size() > 6 ? " and some other stuff" : "");
		vars.put("items", items);
		c.speech().reply("inventory", sender, vars);
	}

	private static void stats(Companion c, @Nullable ServerPlayerEntity sender, Map<String, String> vars) {
		Memory m = c.memory();
		int ores = 0;
		int kills = 0;
		int crafted = 0;
		for (Map.Entry<String, Integer> e : m.stats.entrySet()) {
			if (e.getKey().startsWith("mined_")) {
				ores += e.getValue();
			} else if (e.getKey().startsWith("killed_")) {
				kills += e.getValue();
			} else if (e.getKey().startsWith("crafted_")) {
				crafted += e.getValue();
			}
		}
		vars.put("logs", Integer.toString(m.stat("logs_chopped")));
		vars.put("ores", Integer.toString(ores));
		vars.put("kills", Integer.toString(kills));
		vars.put("crafted", Integer.toString(crafted));
		vars.put("deaths", Integer.toString(m.stat("deaths")));
		c.speech().reply("stats", sender, vars);
	}

	private static void found(Companion c, @Nullable ServerPlayerEntity sender, String phrase, Map<String, String> vars) {
		OreKind kind = phrase.isEmpty() ? null : OreKind.byWord(phrase);
		String kindWord = kind != null ? kind.word : (CommandTasks.isWoodWord(phrase) ? "tree" : null);
		BlockPos from = c.player().getBlockPos();
		List<Memory.Sighting> list = new ArrayList<>();
		for (Memory.Sighting s : c.memory().sightings) {
			if (s.dim.equals(c.dimension()) && (kindWord == null || s.kind.equals(kindWord))) {
				list.add(s);
			}
		}
		vars.put("item", kind != null ? kind.noun() : phrase.isEmpty() ? "anything interesting" : phrase);
		if (list.isEmpty()) {
			c.speech().reply("found.none", sender, vars);
			return;
		}
		list.sort((a, b) -> Double.compare(a.pos().getSquaredDistance(from), b.pos().getSquaredDistance(from)));
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < Math.min(3, list.size()); i++) {
			Memory.Sighting s = list.get(i);
			parts.add(s.kind + " at " + s.x + ", " + s.y + ", " + s.z);
		}
		vars.put("list", String.join("; ", parts));
		c.speech().reply("found", sender, vars);
	}
}
