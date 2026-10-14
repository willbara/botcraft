package com.botcraft.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexiconTest {
	@Test
	void learnsWordsAndRoutines() {
		Lexicon.clear();
		Lexicon.Lesson l = Lexicon.lesson(IntentParser.normalize("when I say lumber I mean logs"));
		assertEquals("lumber", l.word());
		assertEquals("logs", l.meaning());
		Lexicon.teach(l.word(), l.meaning());
		Intent i = IntentParser.parse("bob get me 10 lumber", List.of("bob")).intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("logs", i.item());
		Lexicon.Lesson r = Lexicon.lesson(IntentParser.normalize("morning routine means get food then light the base"));
		assertEquals("morning routine", r.word());
		Lexicon.teach(r.word(), r.meaning());
		assertEquals(2, IntentParser.parse("bob, morning routine", List.of("bob")).intents().size());
		assertTrue(Lexicon.forget("lumber"));
		Lexicon.clear();
	}

	@Test
	void ignoresOrdinarySentences() {
		assertNull(Lexicon.lesson(IntentParser.normalize("what does that mean")));
		assertNull(Lexicon.lesson(IntentParser.normalize("get 10 logs")));
	}
}
