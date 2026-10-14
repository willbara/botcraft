package com.botcraft.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowupsTest {
	private static final Followups.Last LAST = new Followups.Last(List.of(new Intent(Intent.Type.MINE, "iron", 10)), List.of("Bob"), 0);

	private static Followups.Resolved resolve(String text) {
		return Followups.resolve(IntentParser.normalize(text), LAST);
	}

	@Test
	void changeTheCount() {
		assertEquals(30, resolve("make it 30").intents().get(0).count());
		assertEquals(64, resolve("actually, make it a stack").intents().get(0).count());
		assertEquals(20, resolve("20 instead").intents().get(0).count());
		assertEquals("iron", resolve("make it 30").intents().get(0).item());
	}

	@Test
	void changeOrAddTheThing() {
		assertEquals("gold", resolve("no, I meant gold").intents().get(0).item());
		Followups.Resolved and = resolve("and coal too");
		assertTrue(and.append());
		assertEquals(Intent.Type.MINE, and.intents().get(0).type());
		assertEquals("coal", and.intents().get(0).item());
	}

	@Test
	void repeatAndPassOn() {
		assertEquals(LAST.intents(), resolve("same again").intents());
		assertTrue(resolve("you too").otherAddressee());
		assertNull(resolve("what's the time"));
		assertNull(Followups.resolve("make it 30", null));
	}
}
