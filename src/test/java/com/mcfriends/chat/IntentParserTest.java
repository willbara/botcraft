package com.mcfriends.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentParserTest {
	private static final List<String> NAMES = List.of("Bob", "Alice_2", "herobrine");

	private static IntentParser.Parsed parse(String msg) {
		return IntentParser.parse(msg, NAMES);
	}

	private static Intent.Type type(String msg) {
		return parse(msg).intent().type();
	}

	@Test
	void addressing() {
		assertEquals(List.of("Bob"), parse("Bob, follow me").addressed());
		assertEquals(List.of("Bob"), parse("@bob stop").addressed());
		assertEquals(List.of("Alice_2"), parse("alice_2 come here").addressed());
		assertEquals(List.of(IntentParser.ALL), parse("everyone follow me").addressed());
		assertEquals(List.of(IntentParser.ALL), parse("all of you, stop").addressed());
		assertTrue(parse("follow me").addressed().isEmpty());
		assertTrue(parse("gather all the logs").addressed().isEmpty());
		assertTrue(parse("bobsled time").addressed().isEmpty());
		// The reported bug: an order for Bob must not address herobrine.
		assertEquals(List.of("Bob"), parse("bob, go mine logs").addressed());
	}

	@Test
	void directAddress() {
		assertTrue(parse("Bob, what's up").direct());
		assertTrue(parse("thanks bob").direct());
		assertTrue(parse("hey bob how are you").direct());
		assertFalse(parse("i think bob is lost in the cave somewhere").direct());
	}

	@Test
	void standingOrders() {
		assertEquals(Intent.Type.FOLLOW, type("Bob follow me"));
		assertEquals(Intent.Type.FOLLOW, type("bob let's go"));
		assertEquals(Intent.Type.STOP, type("Bob stop"));
		assertEquals(Intent.Type.STOP, type("bob stop following me"));
		assertEquals(Intent.Type.COME_HERE, type("Bob come here"));
		assertEquals(Intent.Type.COME_HERE, type("bob get over here"));
		assertEquals(Intent.Type.DEFEND, type("bob, defend me"));
		assertEquals(Intent.Type.DEFEND, type("bob help me fight"));
		assertEquals(Intent.Type.CANCEL, type("bob never mind"));
		assertEquals(Intent.Type.CANCEL, type("bob forget it"));
	}

	@Test
	void questions() {
		assertEquals(Intent.Type.WHERE, type("bob where are you?"));
		assertEquals(Intent.Type.WHERE, type("bob what are your coords"));
		assertEquals(Intent.Type.INVENTORY, type("bob what do you have"));
		assertEquals(Intent.Type.INVENTORY, type("bob what's in your inventory"));
		assertEquals(Intent.Type.STATS, type("bob how many logs have you chopped"));
		assertEquals(Intent.Type.HELP, type("bob what can you do"));
		assertEquals(Intent.Type.STATUS, type("bob what are you doing?"));
		Intent found = parse("bob have you seen any diamonds").intent();
		assertEquals(Intent.Type.FOUND, found.type());
		assertEquals("diamonds", found.item());
		assertEquals("iron", parse("bob where is the iron").intent().item());
		assertEquals(Intent.Type.GREETING, type("hi bob"));
		assertEquals(Intent.Type.THANKS, type("thanks bob"));
		assertEquals(Intent.Type.UNKNOWN, type("bob the weather is nice"));
	}

	@Test
	void acquisition() {
		Intent i = parse("Bob gather 10 logs").intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("logs", i.item());
		assertEquals(10, i.count());

		i = parse("bob go chop some trees").intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("trees", i.item());
		assertEquals(-1, i.count());

		i = parse("bob get me a stack of wood").intent();
		assertEquals("wood", i.item());
		assertEquals(64, i.count());

		i = parse("bob mine for coal").intent();
		assertEquals(Intent.Type.MINE, i.type());
		assertEquals("coal", i.item());

		i = parse("bob go mining").intent();
		assertEquals(Intent.Type.MINE, i.type());
		assertEquals("", i.item());

		i = parse("bob craft a wooden pickaxe please").intent();
		assertEquals(Intent.Type.CRAFT, i.type());
		assertEquals("wooden pickaxe", i.item());
		assertEquals(1, i.count());

		i = parse("bob make 4 torches").intent();
		assertEquals(Intent.Type.CRAFT, i.type());
		assertEquals("torches", i.item());
		assertEquals(4, i.count());

		i = parse("bob bring me 5 iron").intent();
		assertEquals(Intent.Type.BRING, i.type());
		assertEquals("iron", i.item());
		assertEquals(5, i.count());

		i = parse("bob give me 5 logs").intent();
		assertEquals(Intent.Type.GIVE, i.type());
		assertEquals("logs", i.item());
		assertEquals(5, i.count());
		assertEquals("all", parse("bob give me everything").intent().item());
	}

	@Test
	void chainedOrders() {
		IntentParser.Parsed p = parse("bob, go get logs and then mine for coal");
		assertEquals(2, p.intents().size());
		assertEquals(Intent.Type.GATHER, p.intents().get(0).type());
		assertEquals(Intent.Type.MINE, p.intents().get(1).type());
		assertEquals("coal", p.intents().get(1).item());

		p = parse("bob chop 10 logs, craft a stone pickaxe and bring me the coal");
		assertEquals(List.of(Intent.Type.GATHER, Intent.Type.CRAFT, Intent.Type.BRING), p.intents().stream().map(Intent::type).toList());

		p = parse("bob come here and give me the logs");
		assertEquals(List.of(Intent.Type.COME_HERE, Intent.Type.GIVE), p.intents().stream().map(Intent::type).toList());

		p = parse("bob also mine 5 iron");
		assertTrue(p.append());
		assertEquals(Intent.Type.MINE, p.intent().type());

		assertFalse(parse("bob mine 5 iron").append());
	}

	@Test
	void huntAndCook() {
		Intent i = parse("bob go hunting").intent();
		assertEquals(Intent.Type.HUNT, i.type());
		i = parse("bob kill 2 cows").intent();
		assertEquals(Intent.Type.HUNT, i.type());
		assertEquals("cows", i.item());
		assertEquals(2, i.count());
		assertEquals("food", parse("bob get us some food").intent().item());
		i = parse("bob cook the beef").intent();
		assertEquals(Intent.Type.SMELT, i.type());
		assertEquals("beef", i.item());
		i = parse("bob smelt 5 iron").intent();
		assertEquals(Intent.Type.SMELT, i.type());
		assertEquals("iron", i.item());
		assertEquals(5, i.count());
		// Defending still wins over hunting words.
		assertEquals(Intent.Type.DEFEND, parse("bob kill it!").intent().type());
	}

	@Test
	void farming() {
		assertEquals(Intent.Type.HARVEST, parse("bob harvest and replant this farm").intent().type());
		assertEquals(Intent.Type.HARVEST, parse("bob can you harvest the wheat").intent().type());
		Intent i = parse("bob make a 4x4 farm").intent();
		assertEquals(Intent.Type.FARM, i.type());
		assertEquals(4, i.count());
		assertEquals("wheat", i.item());
		i = parse("bob, build me a 5 by 5 carrot farm").intent();
		assertEquals(Intent.Type.FARM, i.type());
		assertEquals(5, i.count());
		assertEquals("carrots", i.item());
		assertEquals(Intent.Type.FARM, parse("bob plant some wheat").intent().type());
		// Crafting still works for "make".
		assertEquals(Intent.Type.CRAFT, parse("bob make a wooden hoe").intent().type());
	}

	@Test
	void homeAndSleep() {
		assertEquals(Intent.Type.HOME_SET, type("bob, this is home"));
		assertEquals(Intent.Type.HOME_SET, type("bob set this as home"));
		assertEquals(Intent.Type.HOME_GO, type("bob go home"));
		assertEquals(Intent.Type.SLEEP, type("bob go to sleep"));
	}
}
