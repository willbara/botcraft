package com.mcfriends.brain;

/** What a drive wants. Interrupting objectives preempt (pause) the current base activity. */
public enum Objective {
	FIGHT(true),
	FLEE(true),
	RETREAT(true),
	EAT(true),
	WORK(false),
	FOLLOW(false),
	STAY(false),
	IDLE(false);

	public final boolean interrupts;

	Objective(boolean interrupts) {
		this.interrupts = interrupts;
	}
}
