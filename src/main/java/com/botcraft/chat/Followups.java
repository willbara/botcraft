package com.botcraft.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Short replies that only make sense after the last thing someone said: "make it 30", "and coal
 * too", "no, I meant birch", "same again", "you too". They change or repeat the speaker's last
 * order. Pure Java.
 */
public final class Followups {
	private static final String NUM = "(\\d{1,4}|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty|fifty|a dozen|dozen|a stack|half a stack|two stacks|a couple|a few)";
	private static final Pattern MAKE_IT = Pattern.compile("^(?:no ,? |actually ,? |wait ,? )?(?:make (?:it|that)|change (?:it|that) to|let'?s say|i said|actually) "
		+ NUM + "(?: instead| please| pls)?$|^(?:no ,? |actually ,? )?" + NUM + " (?:instead|then|actually)$");
	private static final Pattern AND_ALSO = Pattern.compile("^(?:and|also|plus|oh and|and also) (?:some |a few |more )?(.+?)(?: too| as well| also)?$");
	private static final Pattern I_MEANT = Pattern.compile("^(?:no ,? |sorry ,? |oops ,? )?i (?:meant|mean|said) (?:the |some )?(.+?)(?: not .+)?$");
	private static final Pattern AGAIN = Pattern.compile("^(?:same (?:again|thing)?|again|do (?:it|that) again|repeat (?:it|that)|one more time|once more|more of the same|another round)(?: please| pls)?$");
	private static final Pattern YOU_TOO = Pattern.compile("^(?:you too|same (?:for|goes for|with) you|you as well|you also|you do (?:it|that) too|and you|me too)(?: please| pls)?$");

	/** The kinds of order a follow-up can change. */
	private static boolean carriesItem(Intent.Type t) {
		return switch (t) {
			case GATHER, MINE, CRAFT, BRING, GIVE, HUNT, SMELT -> true;
			default -> false;
		};
	}

	private Followups() {
	}

	/** What the speaker said last (orders), who it was to, and when (world time). */
	public record Last(List<Intent> intents, List<String> to, long at) {
	}

	/** A resolved follow-up: the intents to act on, and whether they're for someone new ("you too"). */
	public record Resolved(List<Intent> intents, boolean append, boolean otherAddressee) {
	}

	/**
	 * Resolve {@code text} (normalized, names removed) against what the speaker said last.
	 * Returns null if it isn't a follow-up (or there's nothing to follow up on).
	 */
	public static Resolved resolve(String text, Last last) {
		if (last == null || last.intents().isEmpty()) {
			return null;
		}
		String t = text.trim().replaceAll("[?!.]+$", "").replaceAll("\\s+", " ");
		if (YOU_TOO.matcher(t).find()) {
			return new Resolved(last.intents(), false, true);
		}
		if (AGAIN.matcher(t).find()) {
			return new Resolved(last.intents(), false, false);
		}
		Matcher m = MAKE_IT.matcher(t);
		if (m.find()) {
			String n = m.group(1) != null ? m.group(1) : m.group(2);
			int count = IntentParser.count(n);
			if (count <= 0) {
				return null;
			}
			List<Intent> out = new ArrayList<>();
			boolean changed = false;
			for (Intent i : last.intents()) {
				if (carriesItem(i.type()) && !changed) {
					out.add(new Intent(i.type(), i.item(), count, i.target()));
					changed = true;
				} else {
					out.add(i);
				}
			}
			return changed ? new Resolved(out, false, false) : null;
		}
		m = I_MEANT.matcher(t);
		if (m.find()) {
			Intent first = firstWithItem(last.intents());
			if (first == null) {
				return null;
			}
			String item = IntentParser.phrase(m.group(1));
			if (item.isEmpty()) {
				return null;
			}
			List<Intent> out = new ArrayList<>();
			for (Intent i : last.intents()) {
				out.add(i == first ? new Intent(i.type(), item, i.count(), i.target()) : i);
			}
			return new Resolved(out, false, false);
		}
		m = AND_ALSO.matcher(t);
		if (m.find()) {
			Intent first = firstWithItem(last.intents());
			String item = IntentParser.phrase(m.group(1));
			if (first == null || item.isEmpty() || item.split(" ").length > 4) {
				return null;
			}
			// "and coal" after "mine iron" means mine some coal too (after the rest).
			return new Resolved(List.of(new Intent(first.type(), item, IntentParser.count(m.group(1)), first.target())), true, false);
		}
		return null;
	}

	private static Intent firstWithItem(List<Intent> intents) {
		for (Intent i : intents) {
			if (carriesItem(i.type())) {
				return i;
			}
		}
		return null;
	}
}
