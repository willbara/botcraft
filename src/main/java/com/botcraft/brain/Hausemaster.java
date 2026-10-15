package com.botcraft.brain;

import com.botcraft.Companion;

/**
 * Easter egg: Hausemaster plays for itself, an ordinary free companion in every other way (see
 * {@link EasterEggs} for its flags: silent, factionless, unkillable). Its one reflex: popbob comes
 * near, and Hausemaster is somewhere else.
 */
public final class Hausemaster implements Persona {
	@Override
	public void always(Companion c, long now) {
		if (now % 10 != 0) {
			return;
		}
		var pop = Popbob.near(c, 6, false);
		if (pop != null) {
			EasterEggs.vanish(c, pop.getPos(), 48, 96); // wants nothing to do with popbob
		}
	}
}
