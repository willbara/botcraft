package com.mcfriends.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SkinUtilTest {
	@Test
	void uuidMapsToRequestedDefaultSkin() {
		for (String skin : SkinUtil.skinIds()) {
			int index = SkinUtil.skinIndex(skin);
			UUID id = SkinUtil.uuidFor("Bob", index);
			assertEquals(index, SkinUtil.indexForUuid(id), skin);
			assertEquals(3, id.version(), "name-based UUID version kept");
		}
	}

	@Test
	void stableAndDistinct() {
		assertEquals(SkinUtil.uuidFor("Bob", 3), SkinUtil.uuidFor("bob", 3));
		assertNotEquals(SkinUtil.uuidFor("Bob", 3), SkinUtil.uuidFor("Alice", 3));
		assertEquals(-1, SkinUtil.skinIndex("herobrine"));
		assertEquals(0, SkinUtil.skinIndex("alex"));
		assertEquals(15, SkinUtil.skinIndex("steve"));
		assertEquals(6, SkinUtil.skinIndex("steve_slim"));
	}
}
