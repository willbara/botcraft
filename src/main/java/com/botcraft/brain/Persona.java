package com.botcraft.brain;

import com.botcraft.Companion;

/**
 * What makes an easter egg companion different, layered on top of the ordinary companion rather
 * than replacing it. Every tick the companion runs its ordinary routine (jobs, the brain's drives:
 * fighting, fleeing, eating, breathing; and, unless {@link #ownPlans} says otherwise, free mode's
 * plans), so whatever the ordinary companion learns to do (smarter combat, tasks, navigation,
 * survival) the egg does too. The persona only adds its own touches:
 * <ul>
 *   <li>{@link #always}: every tick, before anything else (watching for something, a reflex);</li>
 *   <li>{@link #ownPlans}: whether it decides what to do with its day itself instead of free
 *       mode's plans (jobs and drives still run, so it still fights, eats and runs like anyone);</li>
 *   <li>{@link #act}: its own moves, only while the ordinary routine has nothing on (no fight,
 *       flight, meal or job): see {@link EasterEggs#free}.</li>
 * </ul>
 * Traits that only switch ordinary behaviour on or off (silent, factionless, fearless, unkillable,
 * no armour) are flags on {@link EasterEggs.Kind}, checked where that behaviour lives.
 *
 * <p>To give an egg a new trick, prefer queueing an ordinary job (as popbob's raids queue
 * "steal", Herobrine's signs queue "craft"): the job then gets every improvement the job gets.
 */
public interface Persona {
	/** Every tick, before the ordinary routine. */
	default void always(Companion c, long now) {
	}

	/** True: skip free mode's own plans this tick (jobs and drives still run). */
	default boolean ownPlans(Companion c, long now) {
		return false;
	}

	/** Its own moves, while nothing more urgent is on (see {@link EasterEggs#free}). */
	default void act(Companion c, long now) {
	}
}
