package com.mcfriends.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline keyword/pattern intent parser. Pure Java (no Minecraft classes) so it is unit tested.
 *
 * <p>A message is split into clauses ("get logs and then mine for coal" -> two clauses), each
 * clause becomes one {@link Intent}. Pattern order matters: more specific intents are tried
 * first ("stop following" is STOP, "get over here" is COME_HERE, "give me 5 logs" is GIVE).
 * Item phrases are extracted but not interpreted here; the server knows which items exist.
 */
public final class IntentParser {
	public static final String ALL = "*";

	private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
		Map.entry("one", 1), Map.entry("two", 2), Map.entry("couple", 2), Map.entry("three", 3), Map.entry("four", 4),
		Map.entry("five", 5), Map.entry("few", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
		Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("dozen", 12), Map.entry("twelve", 12), Map.entry("fifteen", 15),
		Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("fifty", 50), Map.entry("stack", 64), Map.entry("lots", 32),
		Map.entry("bunch", 24));

	private static final Pattern ALL_WORDS = Pattern.compile("\\b(everyone|everybody|all of you|you all|you guys|guys|team|y'?all|companions)\\b");
	private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,4})\\b");
	private static final Pattern APPEND = Pattern.compile("^(also|and also|after that|afterwards|when you'?re done|when you are done|next|then)\\b\\s*");
	private static final Pattern CLAUSE_SPLIT = Pattern.compile(
		"\\s*(?:,?\\s*\\band then\\b|,?\\s*\\bthen\\b|,?\\s*\\bafter that\\b|,?\\s*\\bafterwards\\b|,?\\s*\\band also\\b|;|\\.)\\s*");
	private static final Pattern AND_VERB = Pattern.compile(
		"\\s*(?:,|\\band\\b)\\s*(?=(go|get|grab|mine|chop|gather|collect|craft|make|come|follow|bring|give|stay|stop|defend|protect|sleep|dig|find|fetch|cut|build|head|return|wait|smelt|cook|hunt)\\b)");

	private static final Pattern CANCEL = Pattern.compile("\\b(never ?mind|cancel( that| it)?|forget (it|that|about it)|scratch that|abort|drop it|drop everything|stop everything)\\b");
	private static final Pattern FREE = Pattern.compile(
		"\\b(do (your|ur) own thing|go play|you'?re free|live (your|ur) life|be free|go explore|explore|do whatever|have fun|(go )?do something|find something to do|keep (yourself |yourselves )?busy|you can go|dismissed|at ease|free roam|play minecraft|go survive)\\b");
	private static final Pattern HOME_SET = Pattern.compile(
		"\\b((set|make|mark) (this|here)( as| to)?( (your|our|my|the))? home|this is (your |our |my |the )?home|remember this place|home is here)\\b");
	private static final Pattern HOME_GO = Pattern.compile(
		"\\b(go home|go back home|head home|head back home|return home|back home|get home|come home|come on home|go to (the |your )?home|walk home|run home|go to base|back to base)\\b");
	private static final String KINDS = "(ores?|minerals?|metals?|ingots?|wood|logs?|planks|food|meat|tools?|weapons?|armou?r|gear|blocks?|stone|cobble(stone)?|building|junk|misc|everything else|storage)";
	private static final Pattern CHEST_SET = Pattern.compile(
		"\\b(this chest is (for|ours|yours|for (you|us|storage))|this is (our|the|a) (" + KINDS + " )?chest|that'?s (our|the) chest|use (this|that) chest( for)?"
			+ "|(put|keep|store) (the |all the |your )?" + KINDS + " (in|into) (this|that) chest|(put|keep|store) (the |all the |your )?" + KINDS + " here"
			+ "|" + KINDS + " go(es)? (here|in (this|that) chest)|(put|keep|store) (stuff|things|it|everything|your stuff|your things) in (this|that) chest"
			+ "|mark this chest|storage chest)\\b");
	private static final Pattern DEPOSIT = Pattern.compile(
		"\\b((put|store|stash) (your|the|all your|away your) (stuff|things|items|loot|junk)( away| in the chests?)?|put (your|the) (stuff|things|items) away|deposit( (your|everything|it all|your stuff))?|empty your (inventory|pockets|bags?)|unload( your stuff)?|stash (it|everything))\\b");
	private static final Pattern STOCK = Pattern.compile(
		"\\b(what'?s in the chests?|what is in the chests?|what do we have|what have we got|what'?s in (storage|the storage)|check the (chests?|stock|storage)|how much do we have|our stock|the stock|what'?s stored)\\b");
	private static final Pattern LIGHT = Pattern.compile(
		"\\b(light (up )?(the |our )?(base|home|area|place)|place (some )?torches|put (some )?torches (down|up|around)|torch (up )?(the |our )?(base|home|area|place)|it'?s (too )?dark (here|around here))\\b");
	/** "Run away": split up and each go do their own thing somewhere else (handy for testing free mode). */
	private static final Pattern SCATTER = Pattern.compile(
		"\\b(run away|scatter|disperse|spread out|split up|go your separate ways|everyone split|break up)\\b");
	private static final Pattern DANCE = Pattern.compile(
		"\\b(dance|dancing|dance party|boogie|bust a move|show (me|us) (your|some) moves|do a (little )?dance|let'?s dance|get down|hit the dance floor|party time)\\b");
	private static final Pattern SLEEP = Pattern.compile("\\b(go to (sleep|bed)|sleep|bedtime|take a nap|get in bed|hit the hay)\\b");
	private static final Pattern WHERE = Pattern.compile("\\b(where are you|where r u|where you at|where'?re you|your (location|position|coords)|coords|coordinates)\\b");
	private static final Pattern FOUND = Pattern.compile(
		"\\b(what have you (found|seen)|what did you (find|see)|(seen|found|spotted) any|did you (see|find) any|where (is|are|was|were|did you see) (the |some |any )?)");
	private static final Pattern STATS = Pattern.compile("\\b(how many|stats|statistics|what have you done|your record|achievements|how much have you)\\b");
	private static final Pattern INVENTORY = Pattern.compile(
		"\\b(what do you have|what have you got|what'?s in your (inventory|pockets|bag)|inventory|what are you (carrying|holding)|show me your (stuff|items)|your items)\\b");
	private static final Pattern HELP = Pattern.compile("\\b(what can you do|help|commands|your abilities|what do you know)\\b");
	private static final Pattern DEFEND = Pattern.compile(
		"\\b(defend|protect|guard|cover me|watch my back|help me fight|fight (them|it|this|that)|kill (it|them|that|those|this)|attack)\\b");
	private static final Pattern GIVE = Pattern.compile("\\b(give|hand|toss|throw|pass|drop) (me|over|it|them|the|your|those|some|all|everything|\\d+)\\b");
	private static final Pattern HUNT = Pattern.compile(
		"\\b(hunt(ing)?|go hunting|kill (some |a |an |the |\\d+ |a few )?(cows?|pigs?|sheep|chickens?|rabbits?|animals?)|get (us |me )?(some )?(food|meat|dinner|lunch))\\b");
	private static final Pattern SMELT = Pattern.compile("\\b(smelt|cook|roast|bake|grill|fry)\\b");
	/** Wishes: "i need some iron", "we're out of coal", "i'm hungry" (someone should do something about it). */
	private static final Pattern NEED = Pattern.compile(
		"^(?:(i|we)(?: really| still| kinda| also)? (?:need|want|could use|would like|'d like|wanna have)|(?:i'?m|we'?re|i am|we are) (?:out of|low on|short on|running low on|running out of)|(?:can|could|may) (i|we) (?:have|get|borrow))\\b");
	private static final Pattern HUNGRY = Pattern.compile("^(?:i'?m|i am|we'?re|we are)(?: so| really| very| kinda)? (?:hungry|starving|starved)\\b");
	/** Just naming a thing ("wood", "iron pickaxe please"): a request for it. */
	private static final Pattern ITEM_ONLY = Pattern.compile(
		"^(?:(?:some|a|an|more|\\d+|a few|a stack of)\\s+)?((?:oak |birch |spruce |dark oak |jungle |acacia |cherry |mangrove )?(?:wood|logs?|planks?|sticks?|coal|charcoal|iron|iron ingots?|raw iron|gold|copper|diamonds?|emeralds?|redstone|lapis|stone|cobble(?:stone)?|food|meat|steak|beef|pork|chicken|mutton|torch(?:es)?|furnace|crafting table|chest|bed|(?:wooden|stone|iron|golden|gold|diamond) (?:pickaxe|sword|axe|shovel|hoe|helmet|chestplate|leggings|boots)|(?:pickaxe|sword|axe|shovel)))(?:\\s+(?:please|pls|thanks|now))*$");
	private static final Pattern BRING = Pattern.compile("\\b(bring|fetch me|deliver)\\b");
	private static final Pattern CRAFT = Pattern.compile("\\b(craft|make|build|create)\\b");
	private static final Pattern MINE = Pattern.compile("\\b(mine|dig|mining|go mining|look for|search for|find)\\b");
	private static final Pattern GATHER = Pattern.compile("\\b(gather|collect|chop|harvest|lumberjack|cut down|cut|get|fetch|farm|grab|pick up)\\b");
	private static final Pattern STOP = Pattern.compile(
		"\\b(stop|stay|wait|halt|freeze|hold (on|up|still|position)|don'?t move|stand still|stop following|don'?t follow|stay here|stay put|chill)\\b");
	private static final Pattern COME_HERE = Pattern.compile(
		"\\b(come here|come to me|over here|get over here|come back|here boy|here girl|come over|to me)\\b");
	private static final Pattern FOLLOW = Pattern.compile("\\b(follow|come with|come along|with me|let'?s go|tag along|keep up|stick with me)\\b");
	private static final Pattern COME = Pattern.compile("\\bcome\\b");
	private static final Pattern STATUS = Pattern.compile(
		"\\b(status|what are you doing|what'?re you doing|what (are )?(you|u) doing|whatcha doing|wyd|what'?s up|how are you|how r u|you ok|are you ok|you good|health|are you hungry|you hungry|report|how'?s it going|what are you up to|whatcha up to|what are you working on|what'?re you working on|what'?s (you|u) doing|how'?s (you|u) doing|how (are|is) (you|u) doing|how (you|u) doing|what'?s your (task|job|plan)|what is your (task|job|plan)|what'?s the plan|what is the plan|what'?s going on|you busy|are you busy|doing\\?)");
	private static final Pattern JOKE = Pattern.compile("\\b(tell (me |us )?(a |another )?joke|make me laugh|something funny|know any jokes)\\b");
	private static final Pattern WHO = Pattern.compile("\\b(who are you|what'?s your name|what is your name|introduce yourself|who r u)\\b");
	private static final Pattern TIME = Pattern.compile("\\b(what time is it|what'?s the time|is it (day|night)|what day is it)\\b");
	private static final Pattern FAVORITE = Pattern.compile("\\b(?:what'?s|what is|whats) your fav(?:ou?rite)? ([a-z]+(?: [a-z]+)?)");
	private static final Pattern OPINION = Pattern.compile("\\b(?:do you (?:like|love|hate|enjoy)|what do you think (?:of|about)|how do you feel about|thoughts on) (.+)");
	private static final Pattern FEELING = Pattern.compile("\\b(how do you feel|how are you feeling|are you (happy|sad|bored|lonely|tired|scared))\\b");
	private static final Pattern LOVE = Pattern.compile("(\\bi love you\\b|\\blove you\\b|<3|\\bily\\b)");
	private static final Pattern BYE = Pattern.compile("^(bye|goodbye|good night|gn|see you|see ya|cya|later|gtg|logging off|night)\\b|\\b(goodbye|see you later|gotta go)\\b");
	private static final Pattern SORRY = Pattern.compile("\\b(sorry|my bad|apologi[sz]e)\\b");
	private static final Pattern COMPLIMENT = Pattern.compile(
		"\\b(you'?re (awesome|great|amazing|the best|cool|smart|a legend|so good)|you are (awesome|great|amazing|the best|cool|smart)|good (job|work|boy|girl|bot)|well done|nice (one|work|job)|great job|you rock|legend)\\b");
	private static final Pattern INSULT = Pattern.compile(
		"\\b(you suck|you stink|you'?re (dumb|stupid|useless|bad|trash|annoying)|you are (dumb|stupid|useless|bad|trash)|stupid|idiot|dumb|useless|bad bot|noob|trash)\\b");
	private static final Pattern GREETING = Pattern.compile("^(hi|hello|hey|yo|sup|hiya|howdy|greetings|good (morning|evening|afternoon))\\b");
	private static final Pattern THANKS = Pattern.compile("\\b(thanks|thank you|thx|ty|cheers|much appreciated)\\b");

	/** Words dropped from the start of an item phrase. */
	private static final Pattern LEAD_FILLER = Pattern.compile(
		"^(?:(?:me|us|yourself|myself|ourselves|you|for|some|a|an|the|few|couple|of|to|more|another|any|up|out|down|go|lots|bunch|stack|stacks|your|my|our|those|these|his|her|its|all|all of|\\d+|one|two|three|four|five|six|seven|eight|nine|ten|dozen|twelve|fifteen|twenty|thirty|fifty|of)\\s+)+");
	private static final Pattern TRAIL_FILLER = Pattern.compile(
		"\\s+(?:for me|for us|please|pls|now|too|again|here|over here|right now|quickly|asap|real quick|if you can|from (the )?\\w+)$");

	private IntentParser() {
	}

	/**
	 * @param addressed which companions were named ({@link #ALL} for "everyone")
	 * @param append    the message added to existing orders ("also ...", "after that ...")
	 * @param direct    the name was used as a direct address (start/end of the message or @name),
	 *                  not just mentioned in passing; unknown messages only get a reply when direct
	 */
	public record Parsed(List<String> addressed, List<Intent> intents, boolean append, boolean direct) {
		public Intent intent() {
			return intents.isEmpty() ? Intent.of(Intent.Type.UNKNOWN) : intents.get(0);
		}
	}

	public static Parsed parse(String message, Collection<String> companionNames) {
		return parse(message, companionNames, List.of());
	}

	/**
	 * @param otherNames online players (not companions) who can be the target of "go to X",
	 *                   "give X the logs"
	 */
	public static Parsed parse(String message, Collection<String> companionNames, Collection<String> otherNames) {
		String text = expandSlang(normalize(message));
		List<String> addressed = new ArrayList<>();
		boolean direct = false;
		String rest = text;
		// "steve, give alex your logs" / "alex go to steve": the name after give/go to/follow is
		// who it's about, not who we're talking to.
		String target = "";
		List<String> allNames = new ArrayList<>(companionNames);
		allNames.addAll(otherNames);
		for (String name : allNames) {
			Matcher tm = Pattern.compile("\\b(?:give|bring|hand|toss|pass|throw|deliver)(?: (?:it|them|some|the|your|all))* (?:to )?@?("
				+ Pattern.quote(name.toLowerCase(Locale.ROOT)) + ")\\b|\\b(?:go to|come to|go over to|walk to|follow|go with|help|go help|join|find|go find|stay with) @?("
				+ Pattern.quote(name.toLowerCase(Locale.ROOT)) + ")\\b").matcher(rest);
			if (tm.find()) {
				int g = tm.group(1) != null ? 1 : 2;
				target = name;
				rest = rest.substring(0, tm.start(g)) + " " + rest.substring(tm.end(g));
				break;
			}
		}
		for (String name : companionNames) {
			String n = name.toLowerCase(Locale.ROOT);
			Pattern p = Pattern.compile("(^|\\W)@?" + Pattern.quote(n) + "(\\W|$)");
			Matcher m = p.matcher(rest);
			if (m.find()) {
				addressed.add(name);
				direct |= text.startsWith(n) || text.startsWith("@" + n) || text.endsWith(n) || text.contains("@" + n)
					|| text.matches("^(hey|hi|yo|ok|okay|oi)\\s+@?" + Pattern.quote(n) + "\\b.*");
				rest = m.replaceAll(" ");
			}
		}
		if (addressed.isEmpty()) {
			// Typos: "bobb, follow me", "herobrin stop". Only as a direct address (first or last word).
			String[] words = rest.trim().split("[ ,]+");
			if (words.length > 0) {
				for (String name : companionNames) {
					String n = name.toLowerCase(Locale.ROOT);
					String first = words[0].replace("@", "");
					String last = words[words.length - 1].replace("@", "");
					String hit = n.length() >= 4 && first.length() >= 3 && editDistance(first, n) == 1 ? words[0]
						: n.length() >= 4 && last.length() >= 3 && editDistance(last, n) == 1 ? words[words.length - 1] : null;
					if (hit != null) {
						addressed.add(name);
						direct = true;
						rest = rest.replaceFirst(Pattern.quote(hit), " ");
						break;
					}
				}
			}
		}
		if (addressed.isEmpty() && ALL_WORDS.matcher(rest).find()) {
			addressed.add(ALL);
			direct = true;
			// "everyone ok?" / "how's everyone doing": speaking to all of them is speaking to "you".
			rest = ALL_WORDS.matcher(rest).replaceAll(" you ");
		}
		rest = rest.replaceAll("\\s+", " ").trim();
		boolean append = false;
		Matcher am = APPEND.matcher(rest);
		if (am.find()) {
			append = true;
			rest = rest.substring(am.end()).trim();
		}
		List<Intent> intents = new ArrayList<>();
		for (String clause : clauses(rest)) {
			String cl = correctTypos(purpose(clause), companionNames);
			Intent i = intent(cl);
			if (i.type() == Intent.Type.UNKNOWN) {
				i = fallback(cl);
			}
			if (i.type() != Intent.Type.UNKNOWN) {
				if (!target.isEmpty() && (i.type() == Intent.Type.GIVE || i.type() == Intent.Type.COME_HERE
					|| i.type() == Intent.Type.FOLLOW || i.type() == Intent.Type.BRING || i.type() == Intent.Type.DEFEND)) {
					i = i.withTarget(target);
				}
				intents.add(i);
			}
		}
		if (intents.isEmpty() && !target.isEmpty()) {
			intents.add(Intent.of(Intent.Type.COME_HERE).withTarget(target)); // "alex, steve" style: go to them
		}
		if (intents.isEmpty()) {
			intents.add(Intent.of(Intent.Type.UNKNOWN));
		}
		return new Parsed(addressed, intents, append, direct);
	}

	private static final Map<String, String> SLANG = Map.ofEntries(
		Map.entry("u", "you"), Map.entry("ur", "your"), Map.entry("r", "are"), Map.entry("pls", "please"), Map.entry("plz", "please"),
		Map.entry("wat", "what"), Map.entry("wut", "what"), Map.entry("gimme", "give me"), Map.entry("lemme", "let me"),
		Map.entry("wanna", "want to"), Map.entry("gotta", "got to"), Map.entry("ya", "you"), Map.entry("yo", "yo"),
		Map.entry("thru", "through"), Map.entry("tho", "though"), Map.entry("cuz", "because"), Map.entry("bc", "because"),
		Map.entry("rn", "right now"), Map.entry("asap", "now"), Map.entry("idk", "i don't know"), Map.entry("im", "i'm"),
		Map.entry("dont", "don't"), Map.entry("cant", "can't"), Map.entry("wont", "won't"), Map.entry("whats", "what's"),
		Map.entry("wheres", "where's"), Map.entry("hows", "how's"), Map.entry("pick", "pickaxe"), Map.entry("pickax", "pickaxe"));

	/** Common chat shorthand -> plain words, so the patterns only need the plain forms. */
	static String expandSlang(String text) {
		StringBuilder sb = new StringBuilder();
		for (String w : text.split(" ")) {
			String r = SLANG.get(w);
			sb.append(r != null && !(w.equals("pick") && !text.matches(".*\\b(wood|stone|iron|diamond|gold|wooden|golden|a|an|new) pick\\b.*")) ? r : w).append(' ');
		}
		return sb.toString().trim();
	}

	/** Purpose clauses: "make a furnace to smelt iron" -> "smelt iron" (we plan the means ourselves). */
	static String purpose(String clause) {
		Matcher m = Pattern.compile("^(.+?)\\s+(?:in order to|so (?:that )?(?:you|we|i) can|to|for)\\s+(make|craft|build|smelt|cook|get|mine|chop|gather)\\s+(.+)$")
			.matcher(clause);
		if (m.find() && !m.group(1).matches(".*\\b(go|come|walk|head)$")) {
			return m.group(2) + " " + m.group(3);
		}
		return clause;
	}

	/** Words the patterns care about; misspellings of these (one edit) are corrected. */
	private static final List<String> LEXICON = List.of("follow", "stop", "stay", "wait", "come", "here", "gather", "collect", "chop",
		"mine", "mining", "craft", "make", "build", "bring", "give", "defend", "protect", "guard", "hunt", "hunting", "cook", "smelt",
		"sleep", "home", "cancel", "where", "inventory", "status", "explore", "wood", "logs", "coal", "iron", "diamond", "diamonds",
		"stone", "cobblestone", "pickaxe", "sword", "shovel", "torch", "torches", "furnace", "crafting", "table", "chest", "food",
		"copper", "redstone", "emerald", "emeralds", "planks", "sticks", "helmet", "chestplate", "leggings", "boots", "shield",
		"please", "thanks", "everyone", "creeper", "zombie", "skeleton", "spider", "favorite", "favourite", "around", "something",
		"store", "stash", "deposit", "chest", "chests", "storage", "unload");

	static String correctTypos(String clause, Collection<String> names) {
		StringBuilder sb = new StringBuilder();
		outer:
		for (String w : clause.split(" ")) {
			if (w.length() >= 4 && !LEXICON.contains(w)) {
				for (String n : names) {
					if (n.equalsIgnoreCase(w)) {
						sb.append(w).append(' ');
						continue outer;
					}
				}
				for (String l : LEXICON) {
					// Four-letter words only by a swapped pair ("mien"); "some" must not become "come".
					boolean ok = w.length() == 4 ? l.length() == 4 && isSwap(w, l) : Math.abs(l.length() - w.length()) <= 1 && damerau(w, l) == 1;
					if (ok) {
						sb.append(l).append(' ');
						continue outer;
					}
				}
			}
			sb.append(w).append(' ');
		}
		return sb.toString().trim();
	}

	private static boolean isSwap(String a, String b) {
		if (a.length() != b.length()) {
			return false;
		}
		int i = 0;
		while (i < a.length() && a.charAt(i) == b.charAt(i)) {
			i++;
		}
		return i < a.length() - 1 && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
			&& a.substring(i + 2).equals(b.substring(i + 2));
	}

	/** Edit distance counting a swap of two neighbouring letters as one edit ("mien" -> "mine"). */
	static int damerau(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[a.length()][b.length()];
	}

	/**
	 * Last resort when no pattern matched: vote by (stemmed) keywords anywhere in the clause,
	 * e.g. "could you start following me around" -> FOLLOW.
	 */
	static Intent fallback(String clause) {
		String[] words = clause.split(" ");
		for (int i = 0; i < words.length; i++) {
			String rest = String.join(" ", java.util.Arrays.copyOfRange(words, i + 1, words.length));
			Intent.Type t = null;
			for (String stem : stems(words[i])) {
				t = keyword(stem);
				if (t != null) {
					break;
				}
			}
			if (t != null) {
				return switch (t) {
					case MINE, GATHER, CRAFT, BRING, HUNT, SMELT -> new Intent(t, phrase(rest), count(clause));
					default -> Intent.of(t);
				};
			}
		}
		return Intent.of(Intent.Type.UNKNOWN);
	}

	/** "chopping" -> chopping, chopp, chop, choppe...; "making" -> mak, make. */
	private static List<String> stems(String w) {
		List<String> out = new ArrayList<>();
		out.add(w);
		String base = w.replaceAll("(ing|ed|es|s)$", "");
		out.add(base);
		if (base.length() > 2 && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)) {
			out.add(base.substring(0, base.length() - 1));
		}
		out.add(base + "e");
		return out;
	}

	@org.jetbrains.annotations.Nullable
	private static Intent.Type keyword(String stem) {
		return switch (stem) {
				case "follow", "tail", "accompany" -> Intent.Type.FOLLOW;
				case "stop", "stay", "wait", "halt", "freeze" -> Intent.Type.STOP;
				case "mine", "dig" -> Intent.Type.MINE;
				case "chop", "gather", "collect", "harvest" -> Intent.Type.GATHER;
				case "craft", "build", "create", "make" -> Intent.Type.CRAFT;
				case "bring", "fetch" -> Intent.Type.BRING;
				case "defend", "protect", "guard" -> Intent.Type.DEFEND;
				case "hunt" -> Intent.Type.HUNT;
				case "cook", "smelt" -> Intent.Type.SMELT;
				case "sleep" -> Intent.Type.SLEEP;
				case "explore", "wander", "roam" -> Intent.Type.FREE;
				case "cancel", "abort" -> Intent.Type.CANCEL;
				default -> null;
			};
	}

	/** Levenshtein distance, early-out above 2. */
	static int editDistance(String a, String b) {
		if (Math.abs(a.length() - b.length()) > 2) {
			return 3;
		}
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}

	private static final Pattern AFTER_YOU = Pattern.compile("^(?:after|once|when) (?:you(?:'ve| have| are done| finish)?|u) (.+)$");
	private static final Pattern NEXT_VERB = Pattern.compile(
		"(?:,\\s*|\\s)(?:then\\s+)?(?=(go|get|grab|mine|chop|gather|collect|craft|make|come|follow|bring|give|stay|stop|sleep|dig|find|fetch|build|head|return|wait|smelt|cook|hunt|put|place)\\b)");

	static List<String> clauses(String text) {
		// "after you get logs, make a table": the first thing named happens first.
		Matcher after = AFTER_YOU.matcher(text);
		if (after.find()) {
			String body = after.group(1);
			Matcher v = NEXT_VERB.matcher(body);
			// Skip the verb the clause starts with: we want the second action.
			while (v.find()) {
				if (v.start() > 0) {
					List<String> out = new ArrayList<>(clauses(body.substring(0, v.start()).trim()));
					out.addAll(clauses(body.substring(v.end()).trim()));
					return out;
				}
			}
		}
		List<String> out = new ArrayList<>();
		for (String part : CLAUSE_SPLIT.split(text)) {
			for (String sub : AND_VERB.split(part)) {
				String s = sub.trim().replaceAll("^(and|also|please)\\s+", "");
				if (!s.isEmpty()) {
					out.add(s);
				}
			}
		}
		return out;
	}

	/** Lowercase, unify apostrophes, strip punctuation except ' ? and clause separators (, ; .). */
	static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^a-z0-9'?@_ ,;.]", " ")
			.replaceAll("\\.(?=\\s|$)", " . ").replaceAll(",", " , ").replaceAll("\\s+", " ").trim()
			.replaceAll("^[ ,]+|[ ,]+$", "");
	}

	public static Intent intent(String clause) {
		String text = clause.replace(" , ", " ").replace(",", " ").replaceAll("\\s+", " ").trim()
			.replaceAll("\\b(something|anything) to eat\\b|\\bsome grub\\b|\\bgrub\\b", "food");
		if (text.isEmpty()) {
			return Intent.of(Intent.Type.UNKNOWN);
		}
		if (CANCEL.matcher(text).find()) {
			return Intent.of(Intent.Type.CANCEL);
		}
		if (SCATTER.matcher(text).find() && !text.matches(".*\\b(don'?t|do not|never)\\b.*")) {
			return Intent.of(Intent.Type.SCATTER);
		}
		if (FREE.matcher(text).find()) {
			return Intent.of(Intent.Type.FREE);
		}
		if (LIGHT.matcher(text).find()) {
			return Intent.of(Intent.Type.LIGHT);
		}
		if (DANCE.matcher(text).find() && !text.matches(".*\\b(do you|can you|how do you|like to)\\b.*dance.*")) {
			return Intent.of(Intent.Type.DANCE);
		}
		if (CHEST_SET.matcher(text).find()) {
			// "this chest is for ores", "food goes in this chest": the purpose rides along as the item.
			com.mcfriends.colony.Purpose purpose = com.mcfriends.colony.Purpose.fromWords(text.replaceAll("\\bchests?\\b", ""));
			return new Intent(Intent.Type.CHEST_SET, purpose == null ? "" : purpose.name(), -1);
		}
		if (STOCK.matcher(text).find()) {
			return Intent.of(Intent.Type.STOCK);
		}
		if (DEPOSIT.matcher(text).find()) {
			return Intent.of(Intent.Type.DEPOSIT);
		}
		if (HOME_SET.matcher(text).find()) {
			return Intent.of(Intent.Type.HOME_SET);
		}
		if (HOME_GO.matcher(text).find()) {
			return Intent.of(Intent.Type.HOME_GO);
		}
		if (WHERE.matcher(text).find()) {
			return Intent.of(Intent.Type.WHERE);
		}
		Matcher found = FOUND.matcher(text);
		if (found.find()) {
			return new Intent(Intent.Type.FOUND, phrase(text.substring(found.end())), -1);
		}
		if (STATS.matcher(text).find()) {
			return new Intent(Intent.Type.STATS, phrase(text.replaceAll(".*\\bhow (many|much)\\b", "")), -1);
		}
		if (INVENTORY.matcher(text).find()) {
			return Intent.of(Intent.Type.INVENTORY);
		}
		if (DEFEND.matcher(text).find()) {
			return Intent.of(Intent.Type.DEFEND);
		}
		if (HELP.matcher(text).find() && !GATHER.matcher(text).find()) {
			return Intent.of(Intent.Type.HELP);
		}
		if (HUNGRY.matcher(text).find()) {
			return new Intent(Intent.Type.BRING, "food", -1);
		}
		Matcher need = NEED.matcher(text);
		if (need.find()) {
			String item = phrase(text.substring(need.end()));
			if (item.matches("(some |any )?(food|something to eat|anything to eat|meat)")) {
				item = "food";
			}
			if (!item.isEmpty()) {
				// "i need X" is for me (bring it over); "we need X" is for the group (go get it).
				boolean forMe = "i".equals(need.group(1)) || "i".equals(need.group(2)) || text.startsWith("i'm") || text.startsWith("i am");
				if (!forMe && item.equals("food")) {
					return new Intent(Intent.Type.HUNT, "food", count(text)); // hunt and cook some
				}
				return new Intent(forMe ? Intent.Type.BRING : Intent.Type.GATHER, item, count(text));
			}
		}
		Matcher only = ITEM_ONLY.matcher(text);
		if (only.find()) {
			String item = only.group(1);
			return new Intent(item.matches(".*\\b(pickaxe|sword|axe|shovel|hoe|helmet|chestplate|leggings|boots|furnace|crafting table|chest|bed|torch(es)?)$")
				? Intent.Type.CRAFT : Intent.Type.GATHER, item, count(text));
		}
		Matcher give = GIVE.matcher(text);
		if (give.find()) {
			String rest = text.substring(give.start()).replaceFirst("^\\w+\\s+", "");
			String item = rest.matches(".*\\b(everything|all|stuff|all your stuff)\\b.*") ? "all" : phrase(rest);
			if (!item.isEmpty()) {
				return new Intent(Intent.Type.GIVE, item, count(text));
			}
		}
		Matcher hunt = HUNT.matcher(text);
		if (hunt.find()) {
			String m = hunt.group();
			String item = m.matches(".*\\b(food|meat|dinner|lunch)\\b.*") ? "food" : phrase(text.substring(hunt.start()).replaceFirst("^(go )?hunt(ing)?( for)?|^kill", ""));
			return new Intent(Intent.Type.HUNT, item.isEmpty() ? "meat" : item, count(text));
		}
		Matcher smelt = SMELT.matcher(text);
		if (smelt.find()) {
			return new Intent(Intent.Type.SMELT, phrase(text.substring(smelt.end())), count(text));
		}
		Matcher bring = BRING.matcher(text);
		if (bring.find()) {
			return new Intent(Intent.Type.BRING, phrase(text.substring(bring.end())), count(text));
		}
		Matcher craft = CRAFT.matcher(text);
		if (craft.find()) {
			String rest = text.substring(craft.end());
			int n = count(rest);
			if (n < 0 && rest.matches("^\\s*(a|an|another|one)\\b.*")) {
				n = 1;
			}
			return new Intent(Intent.Type.CRAFT, phrase(rest), n);
		}
		Matcher mine = MINE.matcher(text);
		if (mine.find() && !COME_HERE.matcher(text).find()) {
			return new Intent(Intent.Type.MINE, phrase(text.substring(mine.end())), count(text));
		}
		Matcher gather = GATHER.matcher(text);
		if (gather.find() && !COME_HERE.matcher(text).find()) {
			String item = phrase(text.substring(gather.end()));
			if (!item.isEmpty() || text.matches(".*\\b(gather|collect|chop|harvest|lumberjack)\\b.*")) {
				return new Intent(Intent.Type.GATHER, item, count(text));
			}
		}
		if (SLEEP.matcher(text).find()) {
			return Intent.of(Intent.Type.SLEEP);
		}
		if (STOP.matcher(text).find()) {
			return Intent.of(Intent.Type.STOP);
		}
		if (COME_HERE.matcher(text).find()) {
			return Intent.of(Intent.Type.COME_HERE);
		}
		if (FOLLOW.matcher(text).find()) {
			return Intent.of(Intent.Type.FOLLOW);
		}
		if (COME.matcher(text).find()) {
			return Intent.of(Intent.Type.COME_HERE);
		}
		Matcher fav = FAVORITE.matcher(text);
		if (fav.find()) {
			return new Intent(Intent.Type.FAVORITE, fav.group(1).trim(), -1);
		}
		Matcher op = OPINION.matcher(text);
		if (op.find()) {
			return new Intent(Intent.Type.OPINION, phrase(op.group(1)), -1);
		}
		if (JOKE.matcher(text).find()) {
			return Intent.of(Intent.Type.JOKE);
		}
		if (WHO.matcher(text).find()) {
			return Intent.of(Intent.Type.WHO);
		}
		if (TIME.matcher(text).find()) {
			return Intent.of(Intent.Type.TIME);
		}
		if (FEELING.matcher(text).find()) {
			return Intent.of(Intent.Type.FEELING);
		}
		if (STATUS.matcher(text).find()) {
			return Intent.of(Intent.Type.STATUS);
		}
		if (LOVE.matcher(text).find()) {
			return Intent.of(Intent.Type.LOVE);
		}
		if (INSULT.matcher(text).find()) {
			return Intent.of(Intent.Type.INSULT);
		}
		if (COMPLIMENT.matcher(text).find()) {
			return Intent.of(Intent.Type.COMPLIMENT);
		}
		if (SORRY.matcher(text).find()) {
			return Intent.of(Intent.Type.SORRY);
		}
		if (BYE.matcher(text).find()) {
			return Intent.of(Intent.Type.BYE);
		}
		if (THANKS.matcher(text).find()) {
			return Intent.of(Intent.Type.THANKS);
		}
		if (GREETING.matcher(text).find()) {
			return Intent.of(Intent.Type.GREETING);
		}
		return Intent.of(Intent.Type.UNKNOWN);
	}

	/** The item part after a verb: "me a stack of oak logs please" -> "oak logs". */
	static String phrase(String afterVerb) {
		String p = afterVerb.trim().replaceAll("[?]", "").replaceAll("\\s+", " ");
		for (int i = 0; i < 3; i++) {
			p = TRAIL_FILLER.matcher(p).replaceAll("");
		}
		p = LEAD_FILLER.matcher(p).replaceAll("").trim();
		p = p.replaceAll("^(for|of)\\s+", "").trim();
		return p;
	}

	/** Explicit count in the text, or -1. */
	static int count(String text) {
		Matcher m = NUMBER.matcher(text);
		if (m.find()) {
			return Math.max(1, Integer.parseInt(m.group(1)));
		}
		for (String w : text.split(" ")) {
			Integer n = NUMBER_WORDS.get(w);
			if (n != null) {
				return n;
			}
		}
		return -1;
	}
}
