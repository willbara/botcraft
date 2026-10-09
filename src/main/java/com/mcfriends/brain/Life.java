package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.Animals;
import com.mcfriends.memory.Memory;
import com.mcfriends.util.AiLog;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * What a companion does when nobody is telling it what to do: a player-like routine and
 * progression, expressed as ordinary jobs (so any order from the owner simply replaces them).
 *
 * <p><b>Follow mode:</b> light initiative only: while idle near the owner in daytime it may
 * make itself basic tools or keep some wood around.
 *
 * <p><b>Free mode</b> ("do your own thing"): it lives around its home spot:
 * <ol>
 *   <li>at night: sleep in a bed (its own or one near home), else head home and wait it out;</li>
 *   <li>when hungry with no food: harvest the farm, cook raw meat, or hunt and cook;</li>
 *   <li>tool progression: sword, pickaxe, stone tools, furnace, iron pickaxe, iron sword;</li>
 *   <li>settle in: a bed (wool from sheep) and a small wheat farm by home;</li>
 *   <li>stock up: logs, cobblestone, coal, torches;</li>
 *   <li>otherwise explore a little around home.</li>
 * </ol>
 * A goal whose job fails is put aside for a while instead of being retried over and over.
 */
public class Life {
	private static final int FOLLOW_COOLDOWN = 20 * 60 * 3;
	private static final int FREE_DECISION_GAP = 20 * 10;
	private static final int FAIL_BACKOFF = 20 * 60 * 10;
	private static final int HOME_RANGE = 48;

	private final Random random = new Random();
	private long nextDecision;
	private final Map<String, Long> backoff = new HashMap<>();
	@Nullable
	private String lastGoal;

	public record Goal(String key, List<String> jobs, String reason) {
	}

	private long nextFoodAsk;

	public void tick(Companion c, long now) {
		askForFood(c, now);
		float level = c.data.initiative != null ? c.data.initiative : CompanionConfig.get().initiative;
		if (level <= 0 || now < nextDecision || now % 20 != 0) {
			return;
		}
		boolean free = "free".equals(c.data.mode);
		if (!free && !"follow".equals(c.data.mode)) {
			return;
		}
		if (!c.jobs().isIdle() || !c.perception().threats().isEmpty() || c.brain().currentObjective() == Objective.SLEEP) {
			return;
		}
		Goal g;
		if (free) {
			nextDecision = now + FREE_DECISION_GAP;
			g = chooseFree(c, now);
		} else {
			if (c.perception().isNight() || c.perception().ownerDistance() > 24) {
				return;
			}
			nextDecision = now + 200;
			float chance = level * (0.4F + 0.3F * c.data.curiosity + 0.3F * c.data.helpfulness);
			if (random.nextFloat() > chance) {
				return;
			}
			g = chooseTools(c.player().getInventory(), now);
			nextDecision = now + FOLLOW_COOLDOWN;
		}
		if (g == null) {
			return;
		}
		AiLog.log(c.name(), "life: {} -> {} ({})", g.key(), g.jobs(), g.reason());
		lastGoal = g.key();
		c.jobs().append(g.jobs());
		c.speech().event("event.initiative", Map.of("plan", String.join(", then ", g.jobs().stream().map(Jobs::describe).toList()),
			"reason", g.reason()), 20 * 30, free ? 0.5F : 0.8F);
	}

	/** Hungry with nothing to eat: a friend might share before we go hunting. */
	private void askForFood(Companion c, long now) {
		if (now < nextFoodAsk || now % 40 != 0) {
			return;
		}
		CompanionPlayer p = c.player();
		if (p.getHungerManager().getFoodLevel() >= 12 || Inventories.foodCount(p.getInventory()) > 0
			|| !c.perception().threats().isEmpty()) {
			return;
		}
		nextFoodAsk = now + 20 * 60 * 2;
		Companion helper = Sharing.ask(c, "food", "something to eat", 3, false);
		if (helper != null) {
			c.jobs().interruptWith(List.of("await food 3 " + helper.name()));
		}
	}

	/** A job from a life goal failed: leave that goal alone for a while. */
	public void failed(long now) {
		if (lastGoal != null) {
			backoff.put(lastGoal, now + FAIL_BACKOFF);
			AiLog.log("life", "backing off goal {}", lastGoal);
			lastGoal = null;
		}
	}

	public void succeeded() {
		lastGoal = null;
	}

	private boolean allowed(String key, long now) {
		Long until = backoff.get(key);
		return until == null || now >= until;
	}

	// ---- free mode ------------------------------------------------------------------------------

	@Nullable
	private Goal chooseFree(Companion c, long now) {
		CompanionPlayer p = c.player();
		PlayerInventory inv = p.getInventory();
		BlockPos home = home(c);
		String h = home.getX() + " " + home.getY() + " " + home.getZ();
		double fromHome = Math.sqrt(p.getBlockPos().getSquaredDistance(home));

		// 1. Night: bed, else home (armed) or a dug-in shelter.
		if (c.perception().isNight()) {
			if (bedNear(c, home) && allowed("sleep", now)) {
				return new Goal("sleep", List.of("sleep"), "it's late");
			}
			// No bed: armed companions can stand guard at home; everyone else digs in like a
			// player's first night.
			if (Inventories.bestWeaponDamage(inv) >= Drives.ARMED_DAMAGE) {
				if (fromHome > 8 && allowed("home", now)) {
					return new Goal("home", List.of("home"), "it's getting dark");
				}
				return null;
			}
			if (allowed("shelter", now)) {
				return new Goal("shelter", List.of("shelter"), "I'm not facing the night without a sword");
			}
			return null;
		}
		List<Goal> goals = new ArrayList<>();
		// 2. Food.
		int food = Inventories.foodCount(inv);
		if (Inventories.count(inv, s -> Animals.RAW_MEAT.contains(s.getItem())) > 0 && (food < 4 || p.getHungerManager().getFoodLevel() < 16)) {
			goals.add(new Goal("cook", List.of("smelt food 8"), "I've got raw meat to cook"));
		}
		if (p.getHungerManager().getFoodLevel() < 16 && food == 0) {
			goals.add(new Goal("hunt", List.of("hunt meat 3", "smelt food 3"), "I'm getting hungry"));
		}
		// 3. Tool progression, then iron gear.
		Goal tools = chooseTools(inv, now);
		if (tools != null) {
			goals.add(tools);
		}
		boolean stonePick = has(inv, s -> s.getItem() instanceof PickaxeItem && !s.isOf(Items.WOODEN_PICKAXE) && !s.isOf(Items.GOLDEN_PICKAXE));
		boolean ironPick = has(inv, s -> s.isOf(Items.IRON_PICKAXE) || s.isOf(Items.DIAMOND_PICKAXE) || s.isOf(Items.NETHERITE_PICKAXE));
		int rawIron = Inventories.count(inv, s -> s.isOf(Items.RAW_IRON));
		if (rawIron >= 3) {
			goals.add(new Goal("smelt_iron", List.of("smelt iron_ingot " + rawIron), "I should smelt this iron"));
		}
		if (stonePick && !ironPick) {
			goals.add(new Goal("iron_pickaxe", List.of("craft iron_pickaxe 1"), "time to get some iron"));
		}
		if (ironPick && Inventories.bestWeaponDamage(inv) < 6.0) {
			goals.add(new Goal("iron_sword", List.of("craft iron_sword 1"), "an iron sword would be nice"));
		}
		if (ironPick) {
			// One armour piece at a time, for any slot not already at least iron.
			for (net.minecraft.item.Item piece : List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS)) {
				net.minecraft.item.ArmorItem ironPiece = (net.minecraft.item.ArmorItem) piece;
				var worn = p.getEquippedStack(ironPiece.getSlotType());
				boolean weaker = !(worn.getItem() instanceof net.minecraft.item.ArmorItem a) || a.getProtection() < ironPiece.getProtection();
				if (weaker && !has(inv, st -> st.isOf(piece))) {
					String id = com.mcfriends.knowledge.ItemNames.id(piece);
					goals.add(new Goal("armor_" + id, List.of("craft " + id + " 1"), "some armour would keep me alive"));
					break;
				}
			}
		}
		// 4. Settle in: a bed at home.
		if (ironPick && !bedNear(c, home) && !has(inv, s -> s.isIn(net.minecraft.registry.tag.ItemTags.BEDS))) {
			goals.add(new Goal("bed", List.of("craft white_bed 1", "place white_bed " + h), "I'd like a bed at home"));
		} else if (has(inv, s -> s.isIn(net.minecraft.registry.tag.ItemTags.BEDS)) && !bedNear(c, home)) {
			goals.add(new Goal("place_bed", List.of("place white_bed " + h), "let's put my bed down"));
		}
		// 5. Whatever suits where we are (like a player would): wood in a forest, ore in a cave.
		boolean underground = !p.getWorld().isSkyVisible(p.getBlockPos().up());
		boolean caveNear = underground || !c.memory().sightings("cave", p.getBlockPos(), c.dimension(), 40).isEmpty();
		int coal = Inventories.count(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL));
		int iron = rawIron + Inventories.count(inv, s -> s.isOf(Items.IRON_INGOT));
		if (stonePick && caveNear) {
			if (coal < 16) {
				goals.add(new Goal("cave_coal", List.of("mine coal 8"), "there's a cave nearby, good for coal"));
			}
			if (iron < 8) {
				goals.add(new Goal("cave_iron", List.of("mine iron 6"), "let's see if this cave has iron"));
			}
			if (ironPick && Inventories.count(inv, s -> s.isOf(Items.DIAMOND)) < 3) {
				goals.add(new Goal("diamonds", List.of("mine diamond 3"), "time to go looking for diamonds"));
			}
		}
		if (has(inv, s -> s.getItem() instanceof net.minecraft.item.AxeItem) && forestAround(p) && Inventories.count(inv, Inventories::isLog) < 24) {
			goals.add(new Goal("forest_logs", List.of("gather logs 16"), "lots of trees here, good time to stock up on wood"));
		}
		if (coal > 0 && Inventories.count(inv, s -> s.isOf(Items.TORCH)) < 16) {
			goals.add(new Goal("torches", List.of("craft torch 8"), "torches for the dark"));
		}
		for (Goal g : goals) {
			if (allowed(g.key(), now)) {
				return g;
			}
		}
		// 6. Nothing pressing: go have a look at a cave we know, or wander (curious ones more).
		if (allowed("explore", now) && random.nextFloat() < 0.3F + c.data.curiosity * 0.5F) {
			backoff.put("explore", now + 20 * 60); // don't wander off every few seconds
			var caves = c.memory().sightings("cave", home, c.dimension(), 64);
			if (!caves.isEmpty() && stonePick && random.nextBoolean()) {
				var cave = caves.get(random.nextInt(caves.size())).pos();
				return new Goal("explore", List.of("goto " + cave.getX() + " " + cave.getY() + " " + cave.getZ() + " exploring", "mine coal 4"),
					"I want another look at that cave");
			}
			double angle = random.nextDouble() * Math.PI * 2;
			int dist = 16 + random.nextInt(24);
			BlockPos target = home.add((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist));
			int y = p.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());
			if (Math.abs(y - home.getY()) < 24) {
				return new Goal("explore", List.of("goto " + target.getX() + " " + y + " " + target.getZ() + " exploring"), "just looking around");
			}
		}
		if (fromHome > HOME_RANGE && allowed("home", now)) {
			return new Goal("home", List.of("home"), "I've wandered off far enough");
		}
		return null;
	}

	/** Lots of tree tops (logs/leaves) around: we're in a forest. */
	private static boolean forestAround(CompanionPlayer p) {
		int trees = 0;
		BlockPos c = p.getBlockPos();
		for (int dx = -12; dx <= 12; dx += 6) {
			for (int dz = -12; dz <= 12; dz += 6) {
				int y = p.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, c.getX() + dx, c.getZ() + dz) - 1;
				var s = p.getWorld().getBlockState(new BlockPos(c.getX() + dx, y, c.getZ() + dz));
				if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)) {
					trees++;
				}
			}
		}
		return trees >= 6;
	}

	/** Basic tools ladder (also used in follow mode). */
	@Nullable
	private Goal chooseTools(PlayerInventory inv, long now) {
		double weapon = Inventories.bestWeaponDamage(inv);
		boolean pickaxe = has(inv, s -> s.getItem() instanceof PickaxeItem);
		boolean stonePickaxe = has(inv, s -> s.getItem() instanceof PickaxeItem && !s.isOf(Items.WOODEN_PICKAXE) && !s.isOf(Items.GOLDEN_PICKAXE));
		List<Goal> goals = new ArrayList<>();
		if (weapon < 4.0) {
			goals.add(new Goal("sword", List.of("craft wooden_sword 1"), "I don't want to be caught without a weapon"));
		}
		if (!pickaxe) {
			goals.add(new Goal("pickaxe", List.of("craft wooden_pickaxe 1"), "a pickaxe will come in handy"));
		}
		if (pickaxe && !stonePickaxe) {
			goals.add(new Goal("stone_pickaxe", List.of("craft stone_pickaxe 1"), "time to upgrade my tools"));
		}
		if (stonePickaxe && weapon < 5.0) {
			goals.add(new Goal("stone_sword", List.of("craft stone_sword 1"), "a better sword would be nice"));
		}
		if (stonePickaxe && !has(inv, s -> s.getItem() instanceof net.minecraft.item.AxeItem)) {
			goals.add(new Goal("axe", List.of("craft stone_axe 1"), "an axe makes chopping faster"));
		}
		if (goals.isEmpty() && Inventories.count(inv, Inventories::isLog) < 4) {
			goals.add(new Goal("logs", List.of("gather logs 12"), "we could use some wood"));
		}
		for (Goal g : goals) {
			if (allowed(g.key(), now)) {
				return g;
			}
		}
		return null;
	}

	/** Home: the remembered place, else set it here (free companions need a base). */
	public static BlockPos home(Companion c) {
		Memory.Place home = c.memory().place("home");
		if (home != null && home.dim.equals(c.dimension())) {
			return home.pos();
		}
		BlockPos here = c.player().getBlockPos();
		c.memory().setPlace("home", here, c.dimension(), c.player().getWorld().getTime());
		return here;
	}

	private static boolean bedNear(Companion c, BlockPos home) {
		Memory.Place bed = c.memory().place("bed");
		var world = c.player().getWorld();
		if (bed != null && world.getBlockState(bed.pos()).isIn(BlockTags.BEDS)) {
			return true;
		}
		for (BlockPos pos : BlockPos.iterate(home.add(-12, -3, -12), home.add(12, 3, 12))) {
			var s = world.getBlockState(pos);
			if (s.isIn(BlockTags.BEDS) && s.get(BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD && !s.get(BedBlock.OCCUPIED)) {
				return true;
			}
		}
		return false;
	}

	private static boolean has(PlayerInventory inv, Predicate<ItemStack> which) {
		return Inventories.count(inv, which) > 0;
	}
}
