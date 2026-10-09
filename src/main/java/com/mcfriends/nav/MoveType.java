package com.mcfriends.nav;

/** How a path step is entered from the previous one. */
public enum MoveType {
	START,
	WALK,
	/** Step up onto a block more than 0.6 higher (stairs are handled as jumps too). */
	JUMP,
	/** Walk off an edge and drop 1+ blocks. */
	FALL,
	SWIM,
	/** Feet/head blocks must be mined first. */
	BREAK,
	/** A block must be placed under the target first. */
	BRIDGE
}
