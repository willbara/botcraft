package com.botcraft.brain;

/** What a drive wants. Interrupting objectives preempt (pause) the current base activity. */
public enum Objective {
	BREATHE(true),
	FIGHT(true),
	FLEE(true),
	RETREAT(true),
	EAT(true),
	SLEEP(false),
	WORK(false),
	FOLLOW(false),
	STAY(false),
	IDLE(false);

	public final boolean interrupts;

	Objective(boolean interrupts) {
		this.interrupts = interrupts;
	}
}
