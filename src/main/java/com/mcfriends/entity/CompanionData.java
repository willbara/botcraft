package com.mcfriends.entity;

import java.util.UUID;

/**
 * Persistent registry entry for one companion (stored in the world folder as JSON).
 * Position, inventory, health and hunger are stored by vanilla in playerdata/&lt;uuid&gt;.dat
 * because companions are real ServerPlayerEntity instances.
 */
public class CompanionData {
	public String name;
	public UUID uuid;
	public UUID owner;
	public String ownerName;
	/** Default skin id (see SkinUtil) or the name of the player whose skin was copied. */
	public String skin;
	/** Signed "textures" property copied from an online player, if any. */
	public String skinValue;
	public String skinSignature;

	public float chattiness;
	public float bravery;
	public float curiosity;
	public float helpfulness;

	/** Standing order: "follow" or "stay". */
	public String mode = "follow";
	/** Current job spec, e.g. "gather logs 12" (remaining count), or null. Restored after restarts. */
	public String job;
	/** Defend-owner mode ("defend me"). */
	public boolean defendOwner;
}
