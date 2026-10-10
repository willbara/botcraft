package com.botcraft.brain;

import com.botcraft.Companion;

/** Scores how much the companion wants something right now (0..1). */
public interface Drive {
	void evaluate(Companion c, Brain brain, Scores out);
}
