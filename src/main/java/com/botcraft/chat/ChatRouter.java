package com.botcraft.chat;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Jobs;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.CraftPlanner;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.knowledge.OreKind;
import com.botcraft.memory.Memory;
import com.botcraft.util.AiLog;
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
import java.util.regex.Matcher;
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
		List<Companion> targets = new ArrayList<>();
		// "Red team, follow me": everyone in a faction.
		com.botcraft.faction.Faction team = null;
		Matcher tm = TEAM_ADDRESS.matcher(IntentParser.normalize(message));
		if (com.botcraft.faction.Factions.enabled() && tm.find()) {
			team = com.botcraft.faction.Factions.find(tm.group(1));
			if (team != null) {
				message = tm.group(2);
				targets.addAll(com.botcraft.faction.Factions.members(team.id));
			}
		}
		IntentParser.Parsed parsed = IntentParser.parse(message, names, players);
		if (team != null) {
			// (named members on top of the team are fine; the whole team hears it)
		} else if (parsed.addressed().contains(IntentParser.ALL)) {
			for (Companion c : m.all()) {
				if (sender.getUuid().equals(c.data.owner) || leads(sender, c)) {
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
		List<Intent> intents = parsed.intents();
		boolean append = parsed.append();
		boolean direct = parsed.direct() || team != null;
		boolean unknown = intents.size() == 1 && intents.get(0).type() == Intent.Type.UNKNOWN;
		long now = sender.getServer().getOverworld().getTime();

		// Teaching words: "Bob, when I say lumber I mean logs".
		Lexicon.Lesson lesson = targets.isEmpty() ? null : Lexicon.lesson(parsed.rest());
		if (lesson != null) {
			teach(sender, targets.get(0), lesson);
			return;
		}
		// Joining a faction: "Bob, join the red team".
		if (!targets.isEmpty() && joinFaction(sender, targets, parsed.rest())) {
			return;
		}
		// Follow-ups to the last order: "make it 30", "and coal too", "you too, Alice".
		if (unknown) {
			Followups.Last last = LAST_ORDERS.get(sender.getUuid());
			if (last != null && now - last.at() < FOLLOWUP_TICKS) {
				Followups.Resolved r = Followups.resolve(parsed.rest(), last);
				if (r != null) {
					intents = r.intents();
					append = r.append();
					unknown = false;
					direct = true;
					if (targets.isEmpty() && !r.otherAddressee()) {
						for (String n : last.to()) {
							Companion c = m.byName(n);
							if (c != null) {
								targets.add(c);
							}
						}
					}
				}
			}
		}
		if (!unknown && intents.stream().anyMatch(i -> i.type().isOrder()) && !targets.isEmpty()) {
			LAST_ORDERS.put(sender.getUuid(), new Followups.Last(intents, targets.stream().map(Companion::name).toList(), now));
		}
		for (Companion c : targets) {
			if (!c.isOnline() || (unknown && !direct)) {
				continue; // only mentioned in passing
			}
			AiLog.log(c.name(), "heard {} (append={}) from {}: '{}'", intents, append, sender.getGameProfile().getName(), message);
			c.hear(sender, intents, append);
		}
	}

	/** "Red team, ..." / "the blue team: ...". */
	private static final java.util.regex.Pattern TEAM_ADDRESS = java.util.regex.Pattern.compile("^(?:the |hey |ok |okay )?([a-z0-9_]{1,12}) (?:team|faction|squad)\\b(?: ,)? ?(.*)$");
	/** "join the red team", "you're with blue now". */
	private static final java.util.regex.Pattern JOIN = java.util.regex.Pattern.compile(
		"^(?:(?:go )?join|switch to|you'?re (?:with|on|in)|you are (?:with|on|in)|go to|be on) (?:the )?([a-z0-9_]{1,12})(?: team| faction| side)?(?: now| please)?$");
	/** How long a follow-up ("make it 30") still refers to the last order. */
	private static final long FOLLOWUP_TICKS = 20 * 90;
	/** Each player's last order, for follow-ups. */
	private static final Map<java.util.UUID, Followups.Last> LAST_ORDERS = new HashMap<>();

	/** A leader or officer of the companion's faction (outside single mode). */
	private static boolean leads(ServerPlayerEntity sender, Companion c) {
		if (!com.botcraft.faction.Factions.enabled()) {
			return false;
		}
		var rank = com.botcraft.faction.Factions.of(c).rank(sender.getUuidAsString());
		return rank == com.botcraft.faction.Faction.Rank.LEADER || rank == com.botcraft.faction.Faction.Rank.OFFICER;
	}

	/** Learn (or forget) a word, if the speaker may tell this companion what to do. */
	private static void teach(ServerPlayerEntity sender, Companion c, Lexicon.Lesson lesson) {
		Map<String, String> vars = new HashMap<>();
		vars.put("word", lesson.word());
		if (com.botcraft.faction.Authority.of(c, sender) != com.botcraft.faction.Authority.Level.ORDER) {
			c.speech().reply("not_owner", sender, vars);
			return;
		}
		if (lesson.forget()) {
			Lexicon.forget(lesson.word());
			LexiconStore.save(c.server);
			vars.put("meaning", "nothing special");
			c.speech().reply("teach.learned", sender, vars);
			return;
		}
		vars.put("meaning", lesson.meaning());
		// A routine if the meaning is something to do; otherwise a word for a thing.
		IntentParser.Parsed meant = IntentParser.parse(lesson.meaning(), List.of(), List.of());
		boolean routine = meant.intents().stream().anyMatch(i -> i.type() != Intent.Type.UNKNOWN && i.type().isOrder());
		boolean thing = ItemNames.resolve(lesson.meaning()) != null || CommandTasks.isWoodWord(lesson.meaning()) || OreKind.byWord(lesson.meaning()) != null;
		if (!routine && !thing && meant.intents().stream().allMatch(i -> i.type() == Intent.Type.UNKNOWN)) {
			c.speech().reply("teach.unknown", sender, vars);
			return;
		}
		Lexicon.teach(lesson.word(), lesson.meaning());
		LexiconStore.save(c.server);
		AiLog.log(c.name(), "learned '{}' = '{}' from {}", lesson.word(), lesson.meaning(), sender.getGameProfile().getName());
		if (routine) {
			vars.put("plan", lesson.meaning());
		}
		c.speech().reply(routine ? "teach.routine" : "teach.learned", sender, vars);
	}

	/** "Bob, join the red team": move companions to a faction (for those who may order them). @return true if it was that */
	private static boolean joinFaction(ServerPlayerEntity sender, List<Companion> targets, String rest) {
		Matcher jm = JOIN.matcher(rest.trim());
		if (!com.botcraft.faction.Factions.enabled() || !jm.find()) {
			return false;
		}
		var faction = com.botcraft.faction.Factions.find(jm.group(1));
		if (faction == null) {
			return false;
		}
		for (Companion c : targets) {
			Map<String, String> vars = new HashMap<>();
			vars.put("faction", faction.name);
			if (com.botcraft.faction.Authority.of(c, sender) != com.botcraft.faction.Authority.Level.ORDER) {
				c.speech().reply("not_owner", sender, vars);
				continue;
			}
			com.botcraft.faction.Factions.join(c, faction.id);
			com.botcraft.faction.Factions.markDirty();
			c.speech().reply("faction.joined", sender, vars);
		}
		return true;
	}

	/** "Do you trust me?" / "do you trust alex?": what we think of them (personally, and as a faction). */
	private static void trust(Companion c, @Nullable ServerPlayerEntity sender, String about, Map<String, String> vars) {
		if (!about.isEmpty() && (sender == null || !about.equalsIgnoreCase(sender.getGameProfile().getName()))) {
			vars.put("person", about);
			vars.put("feeling", feelingAbout(c, about));
			c.speech().reply("opinion.person", sender, vars);
			return;
		}
		if (sender == null) {
			c.speech().reply("standing.trust", null, vars);
			return;
		}
		String name = sender.getGameProfile().getName();
		float affinity = c.relations().affinity(name);
		var band = com.botcraft.faction.Factions.standing(com.botcraft.faction.Factions.of(c), sender).band;
		boolean owner = sender.getUuid().equals(c.data.owner);
		String key;
		if (band == com.botcraft.faction.Standing.Band.HOSTILE || affinity < -0.2F) {
			key = "standing.distrust";
		} else if (band == com.botcraft.faction.Standing.Band.TRUSTED || (owner && affinity > -0.1F)) {
			key = "standing.trust";
		} else if (band == com.botcraft.faction.Standing.Band.FRIENDLY || affinity > 0.2F) {
			key = "standing.friendly";
		} else {
			key = "standing.neutral";
		}
		c.speech().reply(key, sender, vars);
	}

	/** Someone this companion knows (a remembered person or another companion). */
	private static boolean isPerson(Companion c, String name) {
		if (name == null || name.isBlank() || name.contains(" ")) {
			return false;
		}
		CompanionManager m = CompanionManager.get();
		return c.memory().knownPerson(name) != null || (m != null && m.byName(name) != null);
	}

	/** How a companion feels about someone, in a few words. */
	static String feelingAbout(Companion c, String name) {
		float a = c.relations().affinity(name);
		float t = c.relations().trust(name);
		if (a > 0.5F) {
			return "I really like them.";
		}
		if (a > 0.15F) {
			return "They're alright.";
		}
		if (a < -0.4F) {
			return "Can't stand them.";
		}
		if (a < -0.1F) {
			return "Not my favourite person.";
		}
		if (t < 0.35F) {
			return "I don't trust them.";
		}
		return c.memory().knownPerson(name) == null ? "Don't know them that well." : "They're fine, I guess.";
	}

	/** When an apology was last accepted from each player (they only count every few minutes). */
	private static final Map<String, Long> LAST_APOLOGY = new HashMap<>();

	/**
	 * "Sorry": if they've hurt us or our faction, it helps a little (more from someone we trust).
	 * @return true if there was something to forgive (and we answered)
	 */
	private static boolean apologise(Companion c, ServerPlayerEntity sender) {
		var faction = com.botcraft.faction.Factions.of(c);
		var standing = com.botcraft.faction.Factions.peek(faction, sender.getUuid());
		String name = sender.getGameProfile().getName();
		boolean wronged = standing != null && standing.value < standing.baseline - 1;
		if (!wronged && c.relations().affinity(name) >= 0) {
			return false;
		}
		long now = c.server.getOverworld().getTime();
		String key = faction.id + "|" + sender.getUuidAsString();
		Map<String, String> vars = new HashMap<>();
		if (now - LAST_APOLOGY.getOrDefault(key, -100_000L) < 20 * 60 * 5) {
			c.speech().reply("apology.again", sender, vars);
			return true;
		}
		LAST_APOLOGY.put(key, now);
		float trust = c.relations().trust(name);
		int times = trust > 0.6F ? 2 : 1;
		var before = com.botcraft.faction.Factions.incident(faction, sender, com.botcraft.faction.Standing.Incident.APOLOGY, times);
		c.relations().note(name, com.botcraft.brain.Relationship.Event.APOLOGY);
		c.speech().reply(before != null ? "apology.forgiven" : "apology.accepted", sender, vars);
		return true;
	}

	/** "The base needs 64 coal" / "we have enough cobblestone": change what the base keeps. */
	@Nullable
	private static String colonyWant(Companion c, @Nullable ServerPlayerEntity sender, Intent intent) {
		var colony = com.botcraft.colony.Colony.get();
		Map<String, String> vars = new HashMap<>();
		if (colony == null || !colony.hasBase()) {
			c.speech().reply("colony.no_base", sender, vars);
			return "no base yet";
		}
		var target = com.botcraft.colony.StockTargets.forPhrase(intent.item());
		if (target == null) {
			vars.put("item", intent.item());
			c.speech().reply("colony.unknown", sender, vars);
			return "unknown item " + intent.item();
		}
		int n = intent.count();
		if (n < 0) {
			// "The base needs more coal": a couple of batches more than it has (or wants) now.
			n = Math.max(target.want(colony), colony.count(target.counts())) + 2 * target.batch();
		}
		colony.setWant(target.key(), n);
		AiLog.log(c.name(), "colony now keeps {} {}", n, target.key());
		vars.put("item", target.label());
		vars.put("count", Integer.toString(n));
		c.speech().reply(n == 0 ? "colony.enough" : "colony.want", sender, vars);
		return null;
	}

	/**
	 * Where to head on "run away": 40-64 blocks off, away from whoever said it, each companion on
	 * its own bearing (golden-angle steps by name order, so a crowd fans out evenly). Dry land if
	 * the ground there is loaded.
	 */
	static BlockPos scatterSpot(Companion c, @Nullable ServerPlayerEntity sender) {
		CompanionPlayer p = c.player();
		java.util.Random random = new java.util.Random();
		// One shared starting bearing per order (the same second gives the same one), so the
		// golden-angle steps below fan everyone out instead of each starting somewhere random.
		double base = sender != null && sender.squaredDistanceTo(p) > 1
			? Math.atan2(p.getZ() - sender.getZ(), p.getX() - sender.getX())
			: (c.server.getTicks() / 20) * 0.618034 * Math.PI * 2;
		int index = 0;
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			List<String> names = m.all().stream().map(Companion::name).sorted().toList();
			index = Math.max(0, names.indexOf(c.name()));
		}
		var world = p.getServerWorld();
		BlockPos fallback = null;
		for (int attempt = 0; attempt < 8; attempt++) {
			double angle = base + index * 2.39996 + attempt * Math.PI / 4;
			double dist = 40 + random.nextInt(25);
			int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist);
			int z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
			if (!world.isChunkLoaded(x >> 4, z >> 4)) {
				if (fallback == null) {
					fallback = new BlockPos(x, p.getBlockY(), z);
				}
				continue;
			}
			BlockPos top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			if (fallback == null) {
				fallback = top;
			}
			if (world.getFluidState(top.down()).isEmpty() && world.getFluidState(top).isEmpty()) {
				return top;
			}
		}
		return fallback;
	}

	/**
	 * Carry out the request and reply. {@code sender} is null for console commands.
	 *
	 * @return an error for command feedback, or null
	 */
	@Nullable
	public static String respond(Companion c, @Nullable ServerPlayerEntity sender, List<Intent> intents, boolean append) {
		// For its own faction: its colony, its board (commands and chat arrive outside of its tick).
		String prev = com.botcraft.faction.Scope.enter(com.botcraft.faction.Factions.idOf(c));
		try {
			return respondInScope(c, sender, intents, append);
		} finally {
			com.botcraft.faction.Scope.exit(prev);
		}
	}

	@Nullable
	private static String respondInScope(Companion c, @Nullable ServerPlayerEntity sender, List<Intent> intents, boolean append) {
		Speech speech = c.speech();
		if (com.botcraft.brain.Popbob.is(c)) {
			// The easter egg doesn't take orders, or answer questions nicely.
			speech.reply("popbob.ignore", sender, new java.util.HashMap<>(Map.of("player", sender == null ? "you" : sender.getGameProfile().getName())));
			return null;
		}
		// Who may ask what: the owner anything; others by their standing with its faction.
		com.botcraft.faction.Authority.Level level = com.botcraft.faction.Authority.of(c, sender);
		if (level == com.botcraft.faction.Authority.Level.NONE && sender != null && intents.stream().anyMatch(i -> i.type() == Intent.Type.SORRY)
			&& apologise(c, sender)) {
			return null; // an enemy can still say sorry
		}
		if (level == com.botcraft.faction.Authority.Level.NONE) {
			Map<String, String> vars = new HashMap<>();
			speech.reply("refuse.hostile", sender, vars);
			return "they won't listen to you (you're not on good terms)";
		}
		List<String> jobs = new ArrayList<>();
		String mode = null;
		boolean defend = false;
		BlockPos scatterTo = null;
		String error = null;

		for (Intent intent : intents) {
			Map<String, String> vars = new HashMap<>();
			if (!com.botcraft.faction.Authority.allows(level, intent.type())) {
				boolean order = intent.type().isOrder();
				speech.reply(!order ? "refuse.private" : level == com.botcraft.faction.Authority.Level.REQUEST ? "refuse.request" : "not_owner", sender, vars);
				return order ? "not the owner (and not trusted enough to give orders)" : "they won't tell you that";
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
				case SCATTER -> {
					// Free mode, but first everyone heads off their own way.
					mode = "free";
					if (c.memory().place("home") == null) {
						BlockPos at = c.player().getBlockPos();
						c.memory().setPlace("home", at, c.dimension(), c.server.getOverworld().getTime());
					}
					scatterTo = scatterSpot(c, sender);
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
					com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
					if (colony != null && sender != null) {
						colony.setBase(at, dim); // the owner's home is the shared base
					}
					vars.put("x", Integer.toString(at.getX()));
					vars.put("y", Integer.toString(at.getY()));
					vars.put("z", Integer.toString(at.getZ()));
					speech.reply("ack.home_set", sender, vars);
				}
				case CHEST_SET -> {
					com.botcraft.colony.Purpose purpose = intent.item().isEmpty() ? null : com.botcraft.colony.Purpose.valueOf(intent.item());
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
					com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
					if (colony == null || !colony.hasBase()) {
						vars.put("reason", "we don't have a base yet (say \"this is home\")");
						speech.reply("cant.do", sender, vars);
						error = vars.get("reason");
					} else {
						jobs.add("deposit");
					}
				}
				case LIGHT -> {
					if (com.botcraft.action.Inventories.count(c.player().getInventory(), st -> st.isOf(net.minecraft.item.Items.TORCH)) < 8) {
						jobs.add("craft torch 16");
					}
					jobs.add("light");
				}
				case DANCE -> {
					// A little fun: dance now, then carry on with whatever we were doing.
					c.jobs().interruptWith(List.of("dance"));
					speech.reply("ack.dance", sender, vars);
				}
				case COLONY_WANT -> {
					// Information for everyone: say it once, whoever was addressed.
					if (once("colony_want")) {
						error = colonyWant(c, sender, intent);
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
				case FARM -> {
					var colony = com.botcraft.colony.Colony.get();
					if (colony == null || colony.zone(com.botcraft.colony.Zones.FARM) == null) {
						vars.put("reason", colony == null || !colony.hasBase() ? "we don't have a base yet (say \"this is home\")"
							: "the base hasn't got a farm yet (it's laid out once the base map has seen the area)");
						speech.reply("cant.do", sender, vars);
						error = vars.get("reason");
					} else {
						jobs.add(intent.count() > 0 ? "farm " + intent.count() : "farm");
					}
				}
				case HUNT -> {
					String what = intent.item();
					int n = intent.count() < 0 ? 3 : intent.count();
					if (what.equals("food")) {
						jobs.add("hunt meat " + n);
						jobs.add("smelt food " + n);
					} else if (com.botcraft.knowledge.Animals.byWord(what) == null) {
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
				case TRUST -> trust(c, sender, intent.target(), vars);
				case GREETING -> {
					Memory.Person p = sender == null ? null : c.memory().knownPerson(sender.getGameProfile().getName());
					speech.reply(p != null && p.orders > 3 ? "greeting.friend" : "greeting", sender, vars);
				}
				case THANKS -> {
					if (sender != null) {
						c.memory().person(sender.getGameProfile().getName(), c.server.getOverworld().getTime()).thanks++;
						c.relations().note(sender.getGameProfile().getName(), com.botcraft.brain.Relationship.Event.THANKS);
					}
					speech.reply("thanks", sender, vars);
				}
				case JOKE, COMPLIMENT, INSULT, WHO, LOVE, BYE, TIME, FAVORITE, OPINION, FEELING, SORRY -> {
					if (intent.type() == Intent.Type.COMPLIMENT && sender != null) {
						c.memory().person(sender.getGameProfile().getName(), c.server.getOverworld().getTime()).thanks++;
						c.relations().note(sender.getGameProfile().getName(), com.botcraft.brain.Relationship.Event.PRAISE);
						c.mood().bump(com.botcraft.brain.Mood.Kind.JOY, 0.1F, c.now());
					}
					if (intent.type() == Intent.Type.INSULT && sender != null) {
						c.relations().note(sender.getGameProfile().getName(), com.botcraft.brain.Relationship.Event.INSULT);
						c.mood().bump(com.botcraft.brain.Mood.Kind.ANGER, 0.1F, c.now());
					}
					if (intent.type() == Intent.Type.SORRY && sender != null && apologise(c, sender)) {
						break; // they had something to apologise for: that's the answer
					}
					if (intent.type() == Intent.Type.OPINION && isPerson(c, intent.item())) {
						vars.put("person", intent.item());
						vars.put("feeling", feelingAbout(c, intent.item()));
						speech.reply("opinion.person", sender, vars);
						break;
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
		boolean ordered = intents.stream().anyMatch(i -> i.type().isOrder() && i.type() != Intent.Type.FREE && i.type() != Intent.Type.SCATTER && i.type() != Intent.Type.HOME_SET
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
				speech.reply(defend ? "ack.defend" : "stay".equals(mode) ? "ack.stop" : scatterTo != null ? "ack.scatter"
					: "free".equals(mode) ? "ack.free" : "ack.follow", sender, vars);
			}
		}
		if (scatterTo != null && jobs.isEmpty()) {
			AiLog.log(c.name(), "scattering to {}", scatterTo.toShortString());
			c.jobs().replace(List.of("goto " + scatterTo.getX() + " " + scatterTo.getY() + " " + scatterTo.getZ() + " somewhere else"));
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
		} else if (com.botcraft.knowledge.Gear.group(phrase) != null) {
			// "diamond armour", "iron tools": every piece we don't already have (as good or better),
			// each got the way free mode would get it.
			List<Item> pieces = com.botcraft.knowledge.Gear.group(phrase);
			boolean bring = intent.type() == Intent.Type.BRING;
			List<Item> todo = bring ? pieces : pieces.stream().filter(it -> !com.botcraft.knowledge.Gear.hasAtLeast(c.player(), it)).toList();
			if (todo.isEmpty()) {
				return "I already have " + com.botcraft.knowledge.Gear.describe(phrase);
			}
			List<String> delivers = new ArrayList<>();
			for (Item it : todo) {
				out.add("gear " + ItemNames.id(it)); // planned when its turn comes, with what we have by then
				delivers.add("deliver " + ItemNames.id(it) + " 1");
			}
			if (bring) {
				out.addAll(delivers);
			}
			return null;
		} else {
			Item item = ItemNames.resolve(phrase);
			if (item == null) {
				return "I don't know what " + phrase + " is";
			}
			int count = n < 0 ? 1 : n;
			boolean gear = intent.type() != Intent.Type.BRING && count == 1 && com.botcraft.knowledge.Gear.isGear(item);
			String spec = gear ? "gear " + ItemNames.id(item) : Jobs.sourceSpec(c, item, count);
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
		var asInput = com.botcraft.knowledge.Smelting.forInput(c.server, new ItemStack(item));
		if (asInput != null && !com.botcraft.knowledge.Smelting.smeltable(c.server, item)) {
			item = asInput.getOutput(c.server.getRegistryManager()).getItem(); // "cook beef" -> cooked beef
		}
		if (!com.botcraft.knowledge.Smelting.smeltable(c.server, item)) {
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
		var m = com.botcraft.CompanionManager.get();
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
		@org.jetbrains.annotations.Nullable com.botcraft.colony.Purpose purpose) {
		var colony = com.botcraft.colony.Colony.get();
		if (colony == null || sender == null) {
			return "I can't tell which chest you mean";
		}
		var world = sender.getServerWorld();
		BlockPos chest = null;
		var hit = sender.raycast(5.0, 0.0F, false);
		if (hit instanceof net.minecraft.util.hit.BlockHitResult bh && com.botcraft.colony.Colony.inventory(world, bh.getBlockPos()) != null) {
			chest = bh.getBlockPos();
		}
		if (chest == null) {
			double best = Double.MAX_VALUE;
			for (BlockPos p : BlockPos.iterate(sender.getBlockPos().add(-3, -2, -3), sender.getBlockPos().add(3, 2, 3))) {
				if (com.botcraft.colony.Colony.inventory(world, p) != null && p.getSquaredDistance(sender.getBlockPos()) < best) {
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
		var colony = com.botcraft.colony.Colony.get();
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
			String name = com.botcraft.knowledge.ItemNames.display(e.getKey());
			parts.add(e.getValue() + " " + com.botcraft.knowledge.ItemNames.plural(name, e.getValue()));
		}
		return String.join(", ", parts);
	}
}
