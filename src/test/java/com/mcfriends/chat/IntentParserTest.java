package com.mcfriends.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentParserTest {
	private static final List<String> NAMES = List.of("Bob", "Alice_2");

	private static Intent.Type type(String msg) {
		return IntentParser.parse(msg, NAMES).intent().type();
	}

	@Test
	void addressing() {
		assertEquals(List.of("Bob"), IntentParser.parse("Bob, follow me", NAMES).addressed());
		assertEquals(List.of("Bob"), IntentParser.parse("@bob stop", NAMES).addressed());
		assertEquals(List.of("Alice_2"), IntentParser.parse("alice_2 come here", NAMES).addressed());
		assertEquals(List.of(IntentParser.ALL), IntentParser.parse("everyone follow me", NAMES).addressed());
		assertTrue(IntentParser.parse("follow me", NAMES).addressed().isEmpty());
		// "all" inside an order is not addressing everyone.
		assertTrue(IntentParser.parse("gather all the logs", NAMES).addressed().isEmpty());
		assertEquals(List.of(IntentParser.ALL), IntentParser.parse("all of you, stop", NAMES).addressed());
		// Names must be whole words.
		assertTrue(IntentParser.parse("bobsled time", NAMES).addressed().isEmpty());
	}

	@Test
	void orders() {
		assertEquals(Intent.Type.FOLLOW, type("Bob follow me"));
		assertEquals(Intent.Type.FOLLOW, type("bob let's go"));
		assertEquals(Intent.Type.FOLLOW, type("come with me bob"));
		assertEquals(Intent.Type.STOP, type("Bob stop"));
		assertEquals(Intent.Type.STOP, type("bob stay here"));
		assertEquals(Intent.Type.STOP, type("bob stop following me"));
		assertEquals(Intent.Type.STOP, type("wait bob"));
		assertEquals(Intent.Type.COME_HERE, type("Bob come here"));
		assertEquals(Intent.Type.COME_HERE, type("bob get over here"));
		assertEquals(Intent.Type.COME_HERE, type("bob come"));
		assertEquals(Intent.Type.DEFEND, type("bob defend me"));
		assertEquals(Intent.Type.DEFEND, type("bob protect me!"));
		assertEquals(Intent.Type.STATUS, type("bob what are you doing?"));
		assertEquals(Intent.Type.STATUS, type("Bob, status"));
		assertEquals(Intent.Type.STATUS, type("how are you bob"));
		assertEquals(Intent.Type.GREETING, type("hi bob"));
		assertEquals(Intent.Type.THANKS, type("thanks bob"));
		assertEquals(Intent.Type.UNKNOWN, type("bob the weather is nice"));
	}

	@Test
	void gather() {
		Intent i = IntentParser.parse("Bob gather 10 logs", NAMES).intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("logs", i.item());
		assertEquals(10, i.count());

		i = IntentParser.parse("bob go chop some trees", NAMES).intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("trees", i.item());
		assertEquals(IntentParser.DEFAULT_COUNT, i.count());

		i = IntentParser.parse("bob get me a stack of wood", NAMES).intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("wood", i.item());
		assertEquals(64, i.count());

		i = IntentParser.parse("bob collect five oak logs", NAMES).intent();
		assertEquals(5, i.count());

		i = IntentParser.parse("bob gather diamonds", NAMES).intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("diamonds", i.item());

		i = IntentParser.parse("bob gather pumpkins", NAMES).intent();
		assertEquals("pumpkins", i.item());
	}

	@Test
	void give() {
		Intent i = IntentParser.parse("bob give me 5 logs", NAMES).intent();
		assertEquals(Intent.Type.GIVE, i.type());
		assertEquals("logs", i.item());
		assertEquals(5, i.count());
	}
}
