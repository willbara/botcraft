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
		"\\s*(?:,|\\band\\b)\\s*(?=(go|get|mine|chop|gather|collect|craft|make|come|follow|bring|give|stay|stop|defend|protect|sleep|dig|find|fetch|cut|build|head|return|wait)\\b)");

	private static final Pattern CANCEL = Pattern.compile("\\b(never ?mind|cancel( that| it)?|forget (it|that|about it)|scratch that|abort|drop it)\\b");
	private static final Pattern FREE = Pattern.compile(
		"\\b(do (your|ur) own thing|go play|you'?re free|live (your|ur) life|be free|go explore|explore|do whatever|have fun|you can go|dismissed|at ease|free roam|play minecraft|go survive)\\b");
	private static final Pattern HOME_SET = Pattern.compile(
		"\\b((set|make|mark) (this|here)( as| to)?( (your|our|my|the))? home|this is (your |our |my |the )?home|remember this place|home is here)\\b");
	private static final Pattern HOME_GO = Pattern.compile("\\b(go home|go back home|head home|return home|back home|get home)\\b");
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
	private static final Pattern HARVEST = Pattern.compile("\\b(harvest|replant|reap|pick the (crops|wheat|carrots|potatoes))\\b");
	private static final Pattern FARM = Pattern.compile(
		"\\b((make|build|start|set up|plant|create|till) (a |an |us a |me a |some |the )?(\\d+ ?(x|by) ?\\d+ )?(\\w+ )?(farm|field|garden|crops?|wheat|carrots|potatoes|beetroots?|seeds))\\b");
	private static final Pattern SIZE = Pattern.compile("(\\d+) ?(?:x|by) ?(\\d+)");
	private static final Pattern HUNT = Pattern.compile(
		"\\b(hunt(ing)?|go hunting|kill (some |a |an |the |\\d+ |a few )?(cows?|pigs?|sheep|chickens?|rabbits?|animals?)|get (us |me )?(some )?(food|meat|dinner|lunch))\\b");
	private static final Pattern SMELT = Pattern.compile("\\b(smelt|cook|roast|bake|grill|fry)\\b");
	private static final Pattern BRING = Pattern.compile("\\b(bring|fetch me|deliver)\\b");
	private static final Pattern CRAFT = Pattern.compile("\\b(craft|make|build|create)\\b");
	private static final Pattern MINE = Pattern.compile("\\b(mine|dig|mining|go mining|look for|search for|find)\\b");
	private static final Pattern GATHER = Pattern.compile("\\b(gather|collect|chop|harvest|lumberjack|cut down|cut|get|fetch|farm)\\b");
	private static final Pattern STOP = Pattern.compile(
		"\\b(stop|stay|wait|halt|freeze|hold (on|up|still|position)|don'?t move|stand still|stop following|don'?t follow|stay here|stay put|chill)\\b");
	private static final Pattern COME_HERE = Pattern.compile(
		"\\b(come here|come to me|over here|get over here|come back|here boy|here girl|come over|to me)\\b");
	private static final Pattern FOLLOW = Pattern.compile("\\b(follow|come with|come along|with me|let'?s go|tag along|keep up|stick with me)\\b");
	private static final Pattern COME = Pattern.compile("\\bcome\\b");
	private static final Pattern STATUS = Pattern.compile(
		"\\b(status|what are you doing|what'?re you doing|whatcha doing|wyd|what'?s up|how are you|how r u|you ok|are you ok|you good|health|hungry|report|how'?s it going|what are you up to|doing\\?)");
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
		"^(?:(?:me|us|for|some|a|an|the|few|couple|of|to|more|another|any|up|out|down|go|lots|bunch|stack|stacks|\\d+|one|two|three|four|five|six|seven|eight|nine|ten|dozen|twelve|fifteen|twenty|thirty|fifty|of)\\s+)+");
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
		String text = normalize(message);
		List<String> addressed = new ArrayList<>();
		boolean direct = false;
		String rest = text;
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
			rest = ALL_WORDS.matcher(rest).replaceAll(" ");
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
			Intent i = intent(clause);
			if (i.type() != Intent.Type.UNKNOWN) {
				intents.add(i);
			}
		}
		if (intents.isEmpty()) {
			intents.add(Intent.of(Intent.Type.UNKNOWN));
		}
		return new Parsed(addressed, intents, append, direct);
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

	static List<String> clauses(String text) {
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
		String text = clause.replace(" , ", " ").replace(",", " ").replaceAll("\\s+", " ").trim();
		if (text.isEmpty()) {
			return Intent.of(Intent.Type.UNKNOWN);
		}
		if (CANCEL.matcher(text).find()) {
			return Intent.of(Intent.Type.CANCEL);
		}
		if (FREE.matcher(text).find()) {
			return Intent.of(Intent.Type.FREE);
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
		Matcher give = GIVE.matcher(text);
		if (give.find()) {
			String rest = text.substring(give.start()).replaceFirst("^\\w+\\s+", "");
			String item = rest.matches(".*\\b(everything|all|stuff|all your stuff)\\b.*") ? "all" : phrase(rest);
			if (!item.isEmpty()) {
				return new Intent(Intent.Type.GIVE, item, count(text));
			}
		}
		if (HARVEST.matcher(text).find()) {
			return new Intent(Intent.Type.HARVEST, "", -1);
		}
		Matcher farm = FARM.matcher(text);
		if (farm.find()) {
			Matcher size = SIZE.matcher(text);
			int n = size.find() ? Math.max(Integer.parseInt(size.group(1)), Integer.parseInt(size.group(2))) : -1;
			String crop = text.matches(".*\\bcarrots?\\b.*") ? "carrots" : text.matches(".*\\bpotato(es)?\\b.*") ? "potatoes"
				: text.matches(".*\\bbeet(root)?s?\\b.*") ? "beetroot" : "wheat";
			return new Intent(Intent.Type.FARM, crop, n);
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
