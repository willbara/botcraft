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
import net.minecraft.registry.tag.ItemTags;
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
 *   <li>settle in: a bed of its own at home (wool from sheep);</li>
 *   <li>the base's needs: a request from the {@link Board} it's the best bidder for, taking turns
 *       with its own progression (the target ladder);</li>
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
	/** The board request our current jobs are for, if any. */
	@Nullable
	private Integer boardRequest;
	private String boardHolder = "";
	/** Just finished a board request: give our own progression a turn before the next one. */
	private boolean boardRest;

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
		if (free && c.perception().isNight() && (outdoorErrand(c.brain().job()) || leavingBaseToMine(c))) {
			// Our own errand outdoors and it's getting dark: head in (bed, guard home or dig in),
			// carry on in the morning.
			AiLog.log(c.name(), "life: getting dark, dropping {} for the night", c.brain().jobName());
			c.jobs().clear();
			c.nav().stop();
		}
		if (!c.jobs().isIdle() || !c.perception().threats().isEmpty() || c.brain().currentObjective() == Objective.SLEEP) {
			return;
		}
		Goal g;
		if (!free) {
			// Following: no side projects, but don't starve either.
			Goal food = followFood(c, now);
			if (food != null && !c.perception().isNight() && c.perception().ownerDistance() <= 24) {
				nextDecision = now + 200;
				AiLog.log(c.name(), "life: {} -> {} ({})", food.key(), food.jobs(), food.reason());
				lastGoal = food.key();
				c.jobs().append(food.jobs());
				c.speech().event("event.initiative", Map.of("plan", String.join(", then ", food.jobs().stream().map(Jobs::describe).toList()),
					"reason", food.reason()), 20 * 30, 0.8F);
				return;
			}
		}
		if (free) {
			nextDecision = now + FREE_DECISION_GAP;
			g = chooseFree(c, now);
			if (g == null) {
				boardRest = false; // nothing of our own to do: the board can have us again
			}
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
		if (g.key().equals("board") && boardRequest != null) {
			Board.Request r = Board.holding(c.name());
			c.speech().event("board.take", Map.of("item", r == null ? "that" : r.describe(), "reason", g.reason()), 20 * 30, 0.7F);
			return;
		}
		boardRest = false;
		c.speech().event("event.initiative", Map.of("plan", String.join(", then ", g.jobs().stream().map(Jobs::describe).toList()),
			"reason", g.reason()), 20 * 30, free ? 0.5F : 0.8F);
	}

	/** Hungry with nothing to eat: a friend might share before we go hunting. */
	private void askForFood(Companion c, long now) {
		if (now < nextFoodAsk || now % 40 != 0) {
			return;
		}
		CompanionPlayer p = c.player();
		// Hungry, or hurt and unable to heal (natural healing needs a nearly full hunger bar).
		boolean cantHeal = p.getHealth() < p.getMaxHealth() * 0.6F && p.getHungerManager().getFoodLevel() < 18;
		if ((p.getHungerManager().getFoodLevel() >= 12 && !cantHeal) || Inventories.foodCount(p.getInventory()) > 0
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
			if (lastGoal.equals("board")) {
				// The request goes back on the board (with this failure counting against our next bid
				// for it); we don't need to stay away from the board as a whole.
				finishBoard(false, now);
				boardRest = true;
			} else {
				backoff.put(lastGoal, now + FAIL_BACKOFF);
				AiLog.log("life", "backing off goal {}", lastGoal);
			}
			lastGoal = null;
		}
	}

	public void succeeded(long now) {
		if ("board".equals(lastGoal)) {
			finishBoard(true, now);
			boardRest = true;
		}
		lastGoal = null;
	}

	private void finishBoard(boolean ok, long now) {
		if (boardRequest != null) {
			Board.finished(boardRequest, boardHolder, ok, now);
			boardRequest = null;
		}
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
			if (allowed("sleep", now)) {
				if (bedNear(c, home)) {
					return new Goal("sleep", List.of("sleep"), "it's late");
				}
				// No bed down yet: put ours down, or make one if we've got the wool (sleeping skips
				// the night; standing around in the dark doesn't).
				if (has(inv, s -> s.isIn(ItemTags.BEDS))) {
					return new Goal("sleep", List.of("sleep"), "I'll put my bed down and get some sleep");
				}
				String bed = bedFromWool(c, inv);
				if (bed != null && planksAvailable(c, inv) >= 3) {
					return new Goal("sleep", List.of("craft " + bed + " 1", "sleep"), "I've got the wool for a bed, time to sleep");
				}
			}
			// No bed: armed companions can stand guard at home; everyone else digs in like a
			// player's first night.
			if (Inventories.bestWeaponDamage(inv) >= Drives.ARMED_DAMAGE) {
				if (fromHome > 8 && allowed("home", now)) {
					return new Goal("home", List.of("home"), "it's getting dark");
				}
				// Home for the night: do the chores that need nothing from outside.
				return nightChores(c, inv, now);
			}
			if (allowed("shelter", now)) {
				return new Goal("shelter", List.of("shelter"), "I'm not facing the night without a sword");
			}
			return null;
		}
		List<Goal> goals = new ArrayList<>();
		// Storage: put things away when we're at the base anyway, or come back when our pockets are full.
		Goal store = storeGoal(c, inv, now);
		if (store != null) {
			goals.add(store);
		}
		// Light up the base if it's dark (monsters spawn on dark ground).
		Goal light = lightGoal(c, inv, now);
		if (light != null) {
			goals.add(light);
		}
		// 2. Food.
		// Raw meat is food in a pinch, but it's worth cooking (a steak fills far more than raw beef).
		int raw = Inventories.count(inv, s -> Animals.RAW_MEAT.contains(s.getItem()));
		int food = Inventories.foodCount(inv) - raw;
		if (raw > 0 && (food < 6 || p.getHungerManager().getFoodLevel() < 16)) {
			goals.add(new Goal("cook", List.of("smelt food " + Math.min(raw, 16)), "I've got raw meat to cook"));
		}
		boolean hurt = p.getHealth() < p.getMaxHealth() * 0.6F && p.getHungerManager().getFoodLevel() < 18;
		if ((p.getHungerManager().getFoodLevel() < 16 || hurt) && food == 0 && raw == 0) {
			String fetch = Jobs.withdrawSpec(c, "food", Inventories::isGoodFood, 8);
			goals.add(fetch != null ? new Goal("fetch_food", List.of(fetch), "there's food at home")
				: new Goal("hunt", List.of("hunt meat 4", "smelt food 4"), "I'm getting hungry"));
		}
		// 3. Tool progression, then iron gear.
		Goal tools = chooseTools(inv, now);
		if (tools != null) {
			goals.add(tools);
		}
		// Keep some food in reserve, like a player would: hunt before it's urgent, when we know
		// where animals are (or have nothing at all).
		if (food + raw < 6) {
			String fetch = Jobs.withdrawSpec(c, "food", Inventories::isGoodFood, 8);
			if (fetch != null) {
				goals.add(new Goal("fetch_food", List.of(fetch), "I'll take some food from the chests"));
			} else if (herdKnown(c, 80) || animalsInView(c)) {
				goals.add(new Goal("stock_food", List.of("hunt meat 4", "smelt food 4"), "I should stock up on food"));
			}
		}
		boolean stonePick = has(inv, s -> s.getItem() instanceof PickaxeItem && !s.isOf(Items.WOODEN_PICKAXE) && !s.isOf(Items.GOLDEN_PICKAXE));
		boolean ironPick = has(inv, s -> s.isOf(Items.IRON_PICKAXE) || s.isOf(Items.DIAMOND_PICKAXE) || s.isOf(Items.NETHERITE_PICKAXE));
		int rawIron = Inventories.count(inv, s -> s.isOf(Items.RAW_IRON));
		if (rawIron >= 3) {
			goals.add(new Goal("smelt_iron", List.of("smelt iron_ingot " + rawIron), "I should smelt this iron"));
		}
		// 4. Settle in: a bed of our own at home, once we've got basic tools. Sleeping is how the
		// night gets skipped, and everyone needs their own bed for that. (Wool from sheep.)
		if (stonePick && !ownBed(c)) {
			int carried = Inventories.find(inv, s -> s.isIn(ItemTags.BEDS));
			if (carried >= 0) {
				String id = com.mcfriends.knowledge.ItemNames.id(inv.getStack(carried).getItem());
				goals.add(new Goal("place_bed", List.of("place " + id + " " + h), "let's put my bed down"));
			} else {
				String bed = bedFromWool(c, inv);
				bed = bed != null ? bed : "white_bed";
				goals.add(new Goal("bed", List.of("craft " + bed + " 1", "place " + bed + " " + h), "I'd like a bed of my own at home"));
			}
		}
		// 5. The colony's request board: what the base is short of, if we're the best one to get it.
		// (Taking turns with our own progression, so neither starves.)
		if (!boardRest && goals.stream().noneMatch(g -> allowed(g.key(), now))) {
			Board.Assignment a = Board.take(c, now);
			if (a != null) {
				boardRequest = a.request().id;
				boardHolder = c.name();
				return new Goal("board", a.steps(), a.reason());
			}
		}
		// Progression: the next target on the ladder (iron tools, iron armour, diamonds...), got
		// whichever way the planner finds cheapest right now.
		// (Planned only when nothing more urgent is waiting: planning isn't free.)
		if (stonePick && goals.stream().noneMatch(g -> allowed(g.key(), now))) {
			Goal target = targetGoal(c, now);
			if (target != null) {
				goals.add(target);
			}
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
			var stockC = com.mcfriends.colony.Colony.get();
			if (stockC == null || stockC.count(Inventories::isLog) < 64) {
				goals.add(new Goal("forest_logs", List.of("gather logs 16"), "lots of trees here, good time to stock up on wood"));
			}
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
		var colony = com.mcfriends.colony.Colony.get();
		int storedLogs = colony == null ? 0 : colony.count(Inventories::isLog);
		if (goals.isEmpty() && Inventories.count(inv, Inventories::isLog) + storedLogs < 4 + (storedLogs > 0 ? 12 : 0)) {
			goals.add(new Goal("logs", List.of("gather logs 12"), "we could use some wood"));
		}
		for (Goal g : goals) {
			if (allowed(g.key(), now)) {
				return g;
			}
		}
		return null;
	}

	private static long darkCheckedAt = -10_000;
	private static int darkSpotsAtBase;

	/** The base has dark ground and we have (or can make) torches: light it up. */
	@Nullable
	private Goal lightGoal(Companion c, PlayerInventory inv, long now) {
		var colony = com.mcfriends.colony.Colony.get();
		var p = c.player();
		if (!CompanionConfig.get().placeTorches || colony == null || !colony.hasBase() || !allowed("light_home", now)
			|| !colony.near(p.getServerWorld(), p.getBlockPos(), 48)) {
			return null;
		}
		if (now - darkCheckedAt > 20 * 30) { // shared by everyone, refreshed every 30 s
			darkCheckedAt = now;
			darkSpotsAtBase = com.mcfriends.brain.Lighting.darkSpots(colony.world(), colony.base(),
				com.mcfriends.brain.task.LightHomeTask.RADIUS).size();
		}
		if (darkSpotsAtBase == 0) {
			return null;
		}
		int torches = Inventories.count(inv, s -> s.isOf(Items.TORCH));
		if (torches >= 4) {
			return new Goal("light_home", List.of("light"), "it's dark around the base");
		}
		java.util.Map<net.minecraft.item.Item, Integer> have = new java.util.HashMap<>(com.mcfriends.brain.task.CraftTask.inventoryCounts(inv));
		colony.stock().forEach((item, n) -> have.merge(item, n, Integer::sum));
		if (com.mcfriends.knowledge.CraftPlanner.plan(c.server, have, Items.TORCH, 16).ready()) {
			return new Goal("light_home", List.of("craft torch 16", "light"), "it's dark around the base, I'll make torches");
		}
		return null;
	}

	/** A mining job started at the base at night means walking out into the dark to a cave. */
	private static boolean leavingBaseToMine(Companion c) {
		var colony = com.mcfriends.colony.Colony.get();
		var p = c.player();
		return c.brain().job() instanceof com.mcfriends.brain.task.MineTask && colony != null
			&& colony.near(p.getServerWorld(), p.getBlockPos(), com.mcfriends.colony.Colony.BASE_RADIUS)
			&& p.getServerWorld().isSkyVisible(p.getBlockPos().up());
	}

	/** Put things in the base chests: when we're there with plenty to store, or when we're nearly full. */
	@Nullable
	private Goal storeGoal(Companion c, PlayerInventory inv, long now) {
		var colony = com.mcfriends.colony.Colony.get();
		var p = c.player();
		if (colony == null || !colony.hasBase() || colony.world() != p.getServerWorld() || !allowed("deposit", now)) {
			return null;
		}
		int toStore = com.mcfriends.colony.Kit.storeSlots(inv);
		int empty = com.mcfriends.colony.Kit.emptySlots(inv);
		boolean atBase = colony.near(p.getServerWorld(), p.getBlockPos(), com.mcfriends.colony.Colony.BASE_RADIUS);
		if (atBase && (toStore >= 6 || (toStore > 0 && empty < 6))) {
			return new Goal("deposit", List.of("deposit"), "I'll put my things away");
		}
		if (!atBase && empty <= 3 && toStore >= 4 && colony.near(p.getServerWorld(), p.getBlockPos(), 256)) {
			return new Goal("deposit", List.of("home", "deposit"), "my pockets are full");
		}
		return null;
	}

	/**
	 * Night at the base (armed, no bed): things that need only what we carry and what's in the
	 * chests: store, cook, smelt iron, make iron gear. Nothing that sends us out into the dark.
	 */
	@Nullable
	private Goal nightChores(Companion c, PlayerInventory inv, long now) {
		var colony = com.mcfriends.colony.Colony.get();
		var p = c.player();
		if (colony == null || !colony.hasBase() || !colony.near(p.getServerWorld(), p.getBlockPos(), com.mcfriends.colony.Colony.BASE_RADIUS)) {
			return null;
		}
		if (com.mcfriends.colony.Kit.storeSlots(inv) > 0 && allowed("deposit", now)) {
			return new Goal("deposit", List.of("deposit"), "might as well tidy up");
		}
		Goal light = lightGoal(c, inv, now);
		if (light != null) {
			return light;
		}
		java.util.Map<net.minecraft.item.Item, Integer> have = new java.util.HashMap<>(com.mcfriends.brain.task.CraftTask.inventoryCounts(inv));
		colony.stock().forEach((item, n) -> have.merge(item, n, Integer::sum));
		int raw = have.entrySet().stream().filter(e -> Animals.RAW_MEAT.contains(e.getKey())).mapToInt(java.util.Map.Entry::getValue).sum();
		boolean fuel = have.getOrDefault(Items.COAL, 0) + have.getOrDefault(Items.CHARCOAL, 0) > 0
			|| have.entrySet().stream().anyMatch(e -> new net.minecraft.item.ItemStack(e.getKey()).isIn(net.minecraft.registry.tag.ItemTags.LOGS)
			|| new net.minecraft.item.ItemStack(e.getKey()).isIn(net.minecraft.registry.tag.ItemTags.PLANKS));
		if (raw > 0 && fuel && Inventories.foodCount(inv) - Inventories.count(inv, s -> Animals.RAW_MEAT.contains(s.getItem())) < com.mcfriends.colony.Kit.FOOD
			&& allowed("cook", now)) {
			if (Inventories.count(inv, s -> Animals.RAW_MEAT.contains(s.getItem())) == 0) {
				return new Goal("cook", List.of("withdraw " + rawMeatKey(have) + " " + Math.min(raw, 8), "smelt food " + Math.min(raw, 8)), "dinner time");
			}
			return new Goal("cook", List.of("smelt food " + Math.min(raw, 8)), "dinner time");
		}
		int rawIron = have.getOrDefault(Items.RAW_IRON, 0);
		if (rawIron >= 3 && fuel && allowed("smelt_iron", now)) {
			return new Goal("smelt_iron", List.of("smelt iron_ingot " + Math.min(rawIron, 16)), "a good night to smelt iron");
		}
		for (net.minecraft.item.Item gear : List.of(Items.IRON_PICKAXE, Items.IRON_SWORD, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS,
			Items.IRON_HELMET, Items.IRON_BOOTS)) {
			if (has(inv, s -> s.isOf(gear)) || wearing(p, gear)) {
				continue;
			}
			String id = com.mcfriends.knowledge.ItemNames.id(gear);
			if (allowed("night_" + id, now) && com.mcfriends.knowledge.CraftPlanner.plan(c.server, have, gear, 1).ready()) {
				return new Goal("night_" + id, List.of("craft " + id + " 1"), "there's enough iron in the chests for this");
			}
		}
		return null;
	}

	private static boolean wearing(com.mcfriends.entity.CompanionPlayer p, net.minecraft.item.Item item) {
		for (var s : p.getArmorItems()) {
			if (s.isOf(item)) {
				return true;
			}
		}
		return false;
	}

	/** The raw meat we have most of (for fetching it from the chests). */
	private static String rawMeatKey(java.util.Map<net.minecraft.item.Item, Integer> have) {
		return have.entrySet().stream().filter(e -> Animals.RAW_MEAT.contains(e.getKey())).max(java.util.Map.Entry.comparingByValue())
			.map(e -> com.mcfriends.knowledge.ItemNames.id(e.getKey())).orElse("beef");
	}

	/** Self-chosen jobs that keep a companion outdoors and away from home after dark. */
	private static boolean outdoorErrand(@Nullable Task job) {
		return job instanceof com.mcfriends.brain.task.HuntTask || job instanceof com.mcfriends.brain.task.GatherLogsTask
			|| (job instanceof com.mcfriends.brain.task.GoToTask g && g.label().equals("exploring"));
	}

	/** The last planning decision, for /companion why. */
	@Nullable
	private com.mcfriends.brain.plan.Planner.Decision lastDecision;
	@Nullable
	private String lastTarget;

	@Nullable
	public com.mcfriends.brain.plan.Planner.Decision lastDecision() {
		return lastDecision;
	}

	@Nullable
	public String lastTarget() {
		return lastTarget;
	}

	/** The next progression target and the cheapest way to reach it. */
	@Nullable
	private Goal targetGoal(Companion c, long now) {
		var target = com.mcfriends.brain.plan.Targets.next(c, t -> allowed("target_" + com.mcfriends.knowledge.ItemNames.id(t.item()), now));
		if (target == null) {
			return null;
		}
		var decision = com.mcfriends.brain.plan.Planner.acquire(c, target.item(), 1);
		lastDecision = decision;
		lastTarget = target.tier() + ": " + target.name();
		String key = "target_" + com.mcfriends.knowledge.ItemNames.id(target.item());
		if (!decision.chosen().possible() || decision.chosen().steps().isEmpty()) {
			AiLog.log(c.name(), "life: can't plan {} ({})", target.name(), decision.chosen().note());
			backoff.put(key, now + FAIL_BACKOFF);
			return null;
		}
		AiLog.log(c.name(), "plan for {}: {} ({}s) {} | also {}", target.name(), decision.chosen().method(),
			Math.round(decision.chosen().cost()), decision.chosen().steps(), decision.considered().stream()
				.filter(o -> o != decision.chosen()).map(o -> o.method() + " " + (o.possible() ? Math.round(o.cost()) + "s" : "no")).toList());
		return new Goal(key, decision.chosen().steps(), target.tier() + " next: " + target.name());
	}

	/** Hungry with nothing to eat while following: cook what we have, else hunt nearby. */
	@Nullable
	private Goal followFood(Companion c, long now) {
		var p = c.player();
		PlayerInventory inv = p.getInventory();
		if (p.getHungerManager().getFoodLevel() >= 14 || Inventories.foodCount(inv) > 0) {
			return null;
		}
		if (Inventories.count(inv, s -> Animals.RAW_MEAT.contains(s.getItem())) > 0 && allowed("cook", now)) {
			return new Goal("cook", List.of("smelt food 4"), "I've got raw meat to cook");
		}
		String fetch = Jobs.withdrawSpec(c, "food", Inventories::isGoodFood, 8);
		if (fetch != null && allowed("fetch_food", now)) {
			return new Goal("fetch_food", List.of(fetch), "there's food at home");
		}
		return allowed("hunt", now) ? new Goal("hunt", List.of("hunt meat 3", "smelt food 3"), "I'm getting hungry") : null;
	}

	/** Three or more food animals within 32 blocks right now. */
	private static boolean animalsInView(Companion c) {
		var p = c.player();
		return p.getServerWorld().getEntitiesByClass(net.minecraft.entity.passive.AnimalEntity.class, p.getBoundingBox().expand(32, 12, 32),
			a -> Animals.FOOD_ANIMALS.contains(a.getType()) && !a.isBaby()).size() >= 3;
	}

	/** We remember animals somewhere within {@code range} blocks. */
	private static boolean herdKnown(Companion c, double range) {
		return !c.memory().sightings("herd", c.player().getBlockPos(), c.dimension(), range).isEmpty();
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

	/**
	 * We have a bed of our own: the one we remember is still there and no other companion
	 * thinks it's theirs (two sharing one bed means one of them is up all night).
	 */
	static boolean ownBed(Companion c) {
		Memory.Place bed = c.memory().place("bed");
		if (bed == null || !c.player().getWorld().getBlockState(bed.pos()).isIn(BlockTags.BEDS)) {
			return false;
		}
		com.mcfriends.CompanionManager m = com.mcfriends.CompanionManager.get();
		if (m != null) {
			for (Companion o : m.all()) {
				Memory.Place theirs = o == c ? null : o.memory().place("bed");
				if (theirs != null && theirs.pos().equals(bed.pos()) && o.name().compareToIgnoreCase(c.name()) < 0) {
					return false; // shared: the one first by name keeps it, the other makes their own
				}
			}
		}
		return true;
	}

	/**
	 * A bed we could make from wool we carry or have at the base: three of one colour gives
	 * that colour's bed (e.g. "white_bed"), else null.
	 */
	@Nullable
	static String bedFromWool(Companion c, PlayerInventory inv) {
		com.mcfriends.colony.Colony colony = com.mcfriends.colony.Colony.get();
		for (net.minecraft.util.DyeColor color : net.minecraft.util.DyeColor.values()) {
			net.minecraft.item.Item wool = net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(color.getName() + "_wool"));
			int n = Inventories.count(inv, s -> s.isOf(wool));
			if (n < 3 && colony != null && colony.hasBase()) {
				n += colony.count(s -> s.isOf(wool));
			}
			if (n >= 3) {
				return color.getName() + "_bed";
			}
		}
		return null;
	}

	/** Planks we could have for crafting: carried or stored, logs counting as four. */
	private static int planksAvailable(Companion c, PlayerInventory inv) {
		int n = Inventories.count(inv, s -> s.isIn(ItemTags.PLANKS)) + 4 * Inventories.count(inv, Inventories::isLog);
		com.mcfriends.colony.Colony colony = com.mcfriends.colony.Colony.get();
		if (n < 3 && colony != null && colony.hasBase()) {
			n += colony.count(s -> s.isIn(ItemTags.PLANKS)) + 4 * colony.count(Inventories::isLog);
		}
		return n;
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
