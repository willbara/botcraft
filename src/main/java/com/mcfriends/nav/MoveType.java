package com.mcfriends.nav;

/** How a path step is entered from the previous one (Baritone-style movement set). */
public enum MoveType {
	START,
	/** Same level, straight or diagonal (breaking blocks in the way if the step lists any). */
	WALK,
	/** Up one block onto the next column (jump). */
	JUMP,
	/** Walk off an edge and drop 1+ blocks. */
	FALL,
	/** Dig the block we stand on and drop into it. */
	DOWNWARD,
	/** Tower straight up: jump and place a block under ourselves. */
	PILLAR,
	/** Climb a ladder/vine up or down one block. */
	CLIMB,
	/** Sprint-jump over a 1-2 block gap. */
	PARKOUR,
	SWIM,
	/** Walk onto a block we place first (bridging a gap). */
	BRIDGE
}
