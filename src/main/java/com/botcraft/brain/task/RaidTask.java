package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Colony;
import com.botcraft.colony.Kit;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.faction.Faction;
import com.botcraft.faction.Factions;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Raid an enemy faction's base: go there (fights with its members and anyone hostile happen
 * on their own, as interrupts), then, with griefing on, break in (the path finder may go through
 * what they built) and take the best things from their chests; without griefing it's a skirmish
 * at their base and nothing is taken. Pull out when badly hurt, when our pockets are full, or
 * after a few minutes. The jobs queued after this one bring the loot home.
 */
public class RaidTask extends Task {
	/** How long to stay at their base. */
	private static final long STAY_TICKS = 20 * 60 * 2;
	/** Close enough to their base to count as there. */
	private static final double THERE = 14;

	private enum Phase {
		APPROACH, SKIRMISH, LOOT
	}

	private final String enemy;
	private Phase phase = Phase.APPROACH;
	private final ChestVisit visit = new ChestVisit();
	private final Set<BlockPos> looted = new HashSet<>();
	private int taken;
	private long arrivedAt;
	private int approachTicks;

	public RaidTask(Companion c, String enemy) {
		super(c);
		this.enemy = enemy;
	}

	@Override
	public String name() {
		return "raid";
	}

	@Override
	public String describe() {
		Faction f = Factions.get(enemy);
		String who = f == null ? enemy : f.name;
		return switch (phase) {
			case APPROACH -> "on the way to raid " + who;
			case SKIRMISH -> "raiding " + who;
			case LOOT -> "looting " + who + "'s chests (" + taken + " taken)";
		};
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 6;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		Colony them = Colony.of(enemy);
		if (them == null || !them.hasBase() || them.world() != p.getServerWorld()) {
			return fail("I can't find their base");
		}
		if (!Factions.hostile(Factions.idOf(c), enemy)) {
			return quietSuccess(); // made peace in the meantime
		}
		if (p.getHealth() < p.getMaxHealth() * 0.4F) {
			AiLog.log(c.name(), "raid: too hurt, pulling out");
			c.speech().event("raid.retreat", Map.of(), 20 * 30, 0.9F);
			return Status.SUCCESS;
		}
		BlockPos base = them.base();
		double d = Math.sqrt(p.getBlockPos().getSquaredDistance(base));
		switch (phase) {
			case APPROACH -> {
				approachTicks++;
				if (d <= THERE) {
					arrivedAt = c.now();
					c.nav().stop();
					phase = CompanionConfig.get().griefing ? Phase.LOOT : Phase.SKIRMISH;
					c.speech().event("raid.arrive", Map.of("faction", factionName()), 20 * 30, 0.8F);
					return Status.RUNNING;
				}
				if (approachTicks == 1 || c.nav().state() == Navigator.State.IDLE) {
					c.nav().moveTo(Goal.near(base, THERE - 4), true);
				}
				if (c.nav().state() == Navigator.State.FAILED && approachTicks > 20 * 30) {
					return fail("I couldn't get to their base");
				}
				return Status.RUNNING;
			}
			case SKIRMISH -> {
				// Hold their ground for a while; enemies are fought as they come (interrupts).
				if (c.now() - arrivedAt > STAY_TICKS) {
					return Status.SUCCESS;
				}
				if (d > THERE && c.nav().state() != Navigator.State.MOVING) {
					c.nav().moveTo(Goal.near(base, THERE - 4), false);
				}
				return Status.RUNNING;
			}
			case LOOT -> {
				return loot(them);
			}
		}
		return Status.RUNNING;
	}

	private Status loot(Colony them) {
		CompanionPlayer p = c.player();
		if (c.now() - arrivedAt > STAY_TICKS || Kit.emptySlots(p.getInventory()) < 3) {
			return finish();
		}
		if (visit.chest() == null) {
			BlockPos next = null;
			double best = Double.MAX_VALUE;
			for (BlockPos chest : them.chests()) {
				if (looted.contains(chest)) {
					continue;
				}
				double dd = chest.getSquaredDistance(p.getBlockPos());
				if (dd < best) {
					best = dd;
					next = chest;
				}
			}
			if (next == null) {
				return finish();
			}
			visit.start(next);
		}
		Inventory chest = visit.tick(c);
		if (visit.failed()) {
			looted.add(visit.chest());
			visit.reset();
			return Status.RUNNING;
		}
		if (chest == null) {
			return Status.RUNNING;
		}
		// The most valuable things first, while there's room.
		for (int pass = 3; pass >= 1; pass--) {
			for (int i = 0; i < chest.size() && Kit.emptySlots(p.getInventory()) > 1; i++) {
				ItemStack s = chest.getStack(i);
				if (s.isEmpty() || value(s) != pass) {
					continue;
				}
				ItemStack take = s.copy();
				int before = take.getCount();
				p.getInventory().insertStack(take);
				int got = before - take.getCount();
				s.decrement(got);
				taken += got;
			}
		}
		chest.markDirty();
		them.touched();
		c.perception().invalidateInventory();
		looted.add(visit.chest());
		visit.reset();
		// They'll remember this.
		Factions.clash(Factions.idOf(c), enemy, 10);
		return Status.RUNNING;
	}

	/** {@link #value} for other packages (how much a raider wants it). */
	public static int worth(ItemStack s) {
		return value(s);
	}

	/** 3 = precious, 2 = useful gear and metal, 1 = food and fuel, 0 = not worth carrying. */
	static int value(ItemStack s) {
		Item it = s.getItem();
		if (it == Items.DIAMOND || it == Items.EMERALD || it == Items.NETHERITE_INGOT || it == Items.NETHERITE_SCRAP || it == Items.GOLD_INGOT
			|| it == Items.ENCHANTED_GOLDEN_APPLE || it == Items.GOLDEN_APPLE || it == Items.TOTEM_OF_UNDYING) {
			return 3;
		}
		if (it == Items.IRON_INGOT || it == Items.RAW_IRON || it == Items.RAW_GOLD || it instanceof ArmorItem || it instanceof SwordItem
			|| it instanceof ToolItem || it == Items.SHIELD || it == Items.BOW || it == Items.ARROW) {
			return 2;
		}
		if (s.isFood() || it == Items.COAL || it == Items.CHARCOAL || it == Items.TORCH) {
			return 1;
		}
		return 0;
	}

	private Status finish() {
		c.nav().stop();
		AiLog.log(c.name(), "raid on {}: took {} item(s) from {} chest(s)", enemy, taken, looted.size());
		if (taken > 0) {
			c.speech().event("raid.loot", Map.of("faction", factionName(), "count", Integer.toString(taken)), 20 * 30, 0.9F);
		}
		return Status.SUCCESS;
	}

	private String factionName() {
		Faction f = Factions.get(enemy);
		return f == null ? enemy : f.name;
	}

	@Nullable
	public String enemy() {
		return enemy;
	}
}
