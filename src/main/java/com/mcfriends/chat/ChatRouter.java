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
		List<String> players = new ArrayList<>();
		for (ServerPlayerEntity pl : sender.getServer().getPlayerManager().getPlayerList()) {
			if (!(pl instanceof CompanionPlayer)) {
				players.add(pl.getGameProfile().getName());
			}
		}
		IntentParser.Parsed parsed = IntentParser.parse(message, names, players);
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
				case FOLLOW -> {
					mode = "follow";
					// "follow alex": follow someone else for a while; "follow me" goes back to the owner.
					c.data.followTarget = intent.target().isEmpty() ? null : intent.target();
				}
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
					ServerPlayerEntity whoTo = intent.target().isEmpty() ? sender : findByName(c, intent.target());
					if (whoTo == null) {
						error = intent.target().isEmpty() ? "Only a player can call a companion over" : "I can't find " + intent.target();
						vars.put("reason", "I can't find " + intent.target());
						speech.reply("cant.do", sender, vars);
					} else {
						BlockPos at = whoTo.getBlockPos();
						jobs.add("goto " + at.getX() + " " + at.getY() + " " + at.getZ() + " over to "
							+ (whoTo == sender ? "you" : whoTo.getGameProfile().getName()));
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
					com.mcfriends.colony.Colony colony = com.mcfriends.colony.Colony.get();
					if (colony != null && sender != null) {
						colony.setBase(at, dim); // the owner's home is the shared base
					}
					vars.put("x", Integer.toString(at.getX()));
					vars.put("y", Integer.toString(at.getY()));
					vars.put("z", Integer.toString(at.getZ()));
					speech.reply("ack.home_set", sender, vars);
				}
				case CHEST_SET -> {
					com.mcfriends.colony.Purpose purpose = intent.item().isEmpty() ? null : com.mcfriends.colony.Purpose.valueOf(intent.item());
					String problem = markChest(c, sender, purpose);
					if (once("chest_set")) {
						if (problem == null) {
							vars.put("purpose", purpose == null ? "" : purpose.label);
							speech.reply(purpose == null ? "ack.chest_set" : "ack.chest_purpose", sender, vars);
						} else {
							vars.put("reason", problem);
							speech.reply("cant.do", sender, vars);
						}
					}
				}
				case DEPOSIT -> {
					com.mcfriends.colony.Colony colony = com.mcfriends.colony.Colony.get();
					if (colony == null || !colony.hasBase()) {
						vars.put("reason", "we don't have a base yet (say \"this is home\")");
						speech.reply("cant.do", sender, vars);
						error = vars.get("reason");
					} else {
						jobs.add("deposit");
					}
				}
				case STOCK -> {
					if (once("stock")) {
						vars.put("list", stockSummary());
						speech.reply(vars.get("list").isEmpty() ? "stock.empty" : "stock", sender, vars);
					}
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
						if (intent.type() == Intent.Type.BRING && !intent.target().isEmpty() && !specs.isEmpty()) {
							String last = specs.remove(specs.size() - 1);
							specs.add(last + " to " + intent.target());
						}
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
					} else if (!intent.target().isEmpty()) {
						// "give alex your logs": walk over to them and hand it over.
						jobs.add("deliver " + what + " " + (intent.count() < 0 ? 64 : intent.count()) + " to " + intent.target());
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
				case JOKE, COMPLIMENT, INSULT, WHO, LOVE, BYE, TIME, FAVORITE, OPINION, FEELING, SORRY -> {
					if (intent.type() == Intent.Type.COMPLIMENT && sender != null) {
						c.memory().person(sender.getGameProfile().getName(), c.server.getOverworld().getTime()).thanks++;
					}
					SmallTalk.reply(c, sender, intent);
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

		// Any order other than "do your own thing" ends free mode, so the companion's own plans
		// never compete with what it was just told to do.
		// (Setting home is information, not a competing order.)
		boolean ordered = intents.stream().anyMatch(i -> i.type().isOrder() && i.type() != Intent.Type.FREE && i.type() != Intent.Type.HOME_SET
			&& i.type() != Intent.Type.CHEST_SET);
		if (ordered && "free".equals(c.data.mode) && (mode == null || "free".equals(mode))) {
			mode = "follow";
			if (!jobs.isEmpty()) {
				c.jobs().clear(); // drop self-chosen jobs; the order's jobs are queued below
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

	/** A player or companion by name (case-insensitive), or null. */
	@Nullable
	public static ServerPlayerEntity findByName(Companion c, String name) {
		for (ServerPlayerEntity p : c.server.getPlayerManager().getPlayerList()) {
			if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
				return p;
			}
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

	private static final java.util.Map<String, Long> LAST_ONCE = new java.util.HashMap<>();

	/** For things said to everyone that only need one answer ("what's in the chests?"). */
	private static boolean once(String key) {
		var m = com.mcfriends.CompanionManager.get();
		long now = m == null ? 0 : m.tickCount();
		Long last = LAST_ONCE.get(key);
		if (last != null && now - last < 40 && now >= last) {
			return false;
		}
		LAST_ONCE.put(key, now);
		return true;
	}

	/** The chest the owner is looking at (or standing right next to) joins the base. @return problem or null */
	@org.jetbrains.annotations.Nullable
	private static String markChest(Companion c, @org.jetbrains.annotations.Nullable ServerPlayerEntity sender,
		@org.jetbrains.annotations.Nullable com.mcfriends.colony.Purpose purpose) {
		var colony = com.mcfriends.colony.Colony.get();
		if (colony == null || sender == null) {
			return "I can't tell which chest you mean";
		}
		var world = sender.getServerWorld();
		BlockPos chest = null;
		var hit = sender.raycast(5.0, 0.0F, false);
		if (hit instanceof net.minecraft.util.hit.BlockHitResult bh && com.mcfriends.colony.Colony.inventory(world, bh.getBlockPos()) != null) {
			chest = bh.getBlockPos();
		}
		if (chest == null) {
			double best = Double.MAX_VALUE;
			for (BlockPos p : BlockPos.iterate(sender.getBlockPos().add(-3, -2, -3), sender.getBlockPos().add(3, 2, 3))) {
				if (com.mcfriends.colony.Colony.inventory(world, p) != null && p.getSquaredDistance(sender.getBlockPos()) < best) {
					best = p.getSquaredDistance(sender.getBlockPos());
					chest = p.toImmutable();
				}
			}
		}
		if (chest == null) {
			return "I don't see a chest there (look at it, or stand next to it)";
		}
		if (!colony.hasBase() || colony.world() != world) {
			colony.setBase(sender.getBlockPos(), world.getRegistryKey().getValue().toString());
		} else if (!colony.near(world, chest, 32)) {
			return "that chest is too far from home";
		}
		colony.addChest(chest);
		if (purpose != null) {
			colony.setPurpose(chest, purpose);
		}
		return null;
	}

	/** "64 cobblestone, 12 iron ingots, ..." (the most plentiful first). */
	private static String stockSummary() {
		var colony = com.mcfriends.colony.Colony.get();
		if (colony == null || !colony.hasBase()) {
			return "";
		}
		var list = new java.util.ArrayList<>(colony.stock().entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		java.util.List<String> parts = new java.util.ArrayList<>();
		for (var e : list) {
			if (parts.size() >= 10) {
				parts.add("and " + (list.size() - 10) + " more kinds of things");
				break;
			}
			String name = com.mcfriends.knowledge.ItemNames.display(e.getKey());
			parts.add(e.getValue() + " " + com.mcfriends.knowledge.ItemNames.plural(name, e.getValue()));
		}
		return String.join(", ", parts);
	}
}
