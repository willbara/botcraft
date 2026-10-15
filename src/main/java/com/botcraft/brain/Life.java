package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.Animals;
import com.botcraft.memory.Memory;
import com.botcraft.util.AiLog;
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
	/** The goals free mode chooses between, and their history (what failed, what's put aside). */
	private final com.botcraft.brain.goal.GoalTree tree = new com.botcraft.brain.goal.GoalTree();
	/** The last decision came out of {@link #tree}'s pick (rather than an early rule like going to bed). */
	private boolean picked;
	@Nullable
	private String previousGoal;
	@Nullable
	private String lastGoal;
	/** The board request our current jobs are for, if any. */
	@Nullable
	private Integer boardRequest;
	private String boardHolder = "";
	/** Just finished a board request: give our own progression a turn before the next one. */
	private boolean boardRest;
	/** The board request we're on was our leader's order (see Leadership): we report back on it. */
	private boolean boardOrdered;

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
		if (free && c.perception().isNight() && (outdoorErrand(c.brain().job()) || (leavingBaseToMine(c) && !"night_mine".equals(lastGoal)))) {
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

	/** Goals that all mean going hunting. */
	private static final java.util.Set<String> HUNTING = java.util.Set.of("hunt", "stock_food");

	/** A job from a life goal failed: leave that goal alone for a while. */
	public void failed(long now) {
		nextDecision = Math.min(nextDecision, now + 40);
		if (lastGoal != null) {
			if (lastGoal.equals("board")) {
				// The request goes back on the board (with this failure counting against our next bid
				// for it); we don't need to stay away from the board as a whole.
				finishBoard(false, now);
				boardRest = true;
			} else {
				tree.failed(lastGoal, now, "");
				if (HUNTING.contains(lastGoal) && com.botcraft.util.Experiments.on("huntBackoff")) {
					// No animals to be found: that's true whichever reason sent us hunting.
					HUNTING.forEach(g -> tree.rest(g, now + FAIL_BACKOFF));
				}
				AiLog.log("life", "backing off goal {}", lastGoal);
			}
			lastGoal = null;
		}
	}

	/**
	 * Our leader gave us a board request to do (see Leadership): take it on like one we'd picked,
	 * if we've nothing else on. True if we did.
	 */
	public boolean directed(Companion c, Board.Assignment a, long now) {
		if (!c.jobs().isIdle() || boardRequest != null) {
			return false;
		}
		boardRequest = a.request().id;
		boardHolder = c.name();
		boardRest = false;
		boardOrdered = true;
		lastGoal = "board";
		AiLog.log(c.name(), "life: the leader asked for {} -> {}", a.request().describe(), a.steps());
		c.jobs().append(a.steps());
		return true;
	}

	/** Still on an order our leader gave us (a board request it handed us). */
	public boolean onOrder() {
		return boardOrdered && boardRequest != null;
	}

	/** Decide again soon rather than at the next scheduled decision (a job just ended, a leader called). */
	public void hurry(long now) {
		nextDecision = Math.min(nextDecision, now + 20);
	}

	/** Something else took over (a raid): the goal we were on isn't ours to finish any more. */
	public void abandon(long now) {
		if ("board".equals(lastGoal)) {
			finishBoard(false, now);
		}
		lastGoal = null;
	}

	public void succeeded(long now) {
		hurry(now); // on to the next thing straight away: no standing about between jobs
		if (lastGoal != null) {
			tree.succeeded(lastGoal);
		}
		if ("farm".equals(lastGoal)) {
			tree.rest("farm", now + 20 * 60 * 3); // the crops need time to grow back
		}
		if ("tame".equals(lastGoal)) {
			tree.rest("tame", now + 20 * 60 * 5); // one new wolf at a time
		}
		if ("clear_home".equals(lastGoal)) {
			tree.rest("clear_home", now + 20 * 30); // a breather (and the next look at home) between clearing trips
		}
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
			if (boardOrdered) {
				boardOrdered = false;
				var m = com.botcraft.CompanionManager.get();
				Companion who = m == null ? null : m.byName(boardHolder);
				if (who != null) {
					com.botcraft.faction.Leadership.reportBack(who, ok, now);
				}
			}
		}
	}

	private boolean allowed(String key, long now) {
		return tree.allowed(key, now);
	}

	/** The goal tree (for /companion goals). */
	public com.botcraft.brain.goal.GoalTree tree() {
		return tree;
	}

	/**
	 * How much this companion cares about each kind of goal: cautious ones about staying alive,
	 * ambitious ones about progress (less when tired), loyal and helpful ones about the base,
	 * curious ones (more when bored) about exploring.
	 */
	private static Map<com.botcraft.brain.goal.GoalTree.Root, Float> weights(Companion c) {
		Personality p = c.brain().personality();
		Mood m = c.mood();
		Map<com.botcraft.brain.goal.GoalTree.Root, Float> w = new java.util.EnumMap<>(com.botcraft.brain.goal.GoalTree.Root.class);
		w.put(com.botcraft.brain.goal.GoalTree.Root.SURVIVE, 1.0F + 0.1F * p.caution());
		// Poorly armed (less than an iron sword, or no armour to speak of), getting armed matters more.
		var cp = c.player();
		boolean underArmed = cp != null && (Inventories.bestWeaponDamage(cp.getInventory()) < 6.0 || cp.getArmor() < 8);
		w.put(com.botcraft.brain.goal.GoalTree.Root.PROGRESS, 0.92F + 0.16F * p.ambition() - 0.1F * m.get(Mood.Kind.FATIGUE) + (underArmed ? 0.1F : 0.0F));
		w.put(com.botcraft.brain.goal.GoalTree.Root.FACTION, 0.92F + 0.08F * p.helpfulness() + 0.08F * p.loyalty());
		w.put(com.botcraft.brain.goal.GoalTree.Root.CURIOSITY, 0.85F + 0.25F * p.curiosity() + 0.15F * m.get(Mood.Kind.BOREDOM));
		w.put(com.botcraft.brain.goal.GoalTree.Root.REST, 1.0F);
		return w;
	}

	// ---- free mode ------------------------------------------------------------------------------

	/** Free mode's choice, recorded in the goal tree (whichever rule made it). */
	@Nullable
	private Goal chooseFree(Companion c, long now) {
		tree.begin(now);
		picked = false;
		Goal g = chooseFreeRules(c, now);
		if (g != null && !picked) {
			tree.chose(com.botcraft.brain.goal.GoalTree.rootOf(g.key()), g.key(), g.reason(), g.jobs(), now);
		}
		if (g != null) {
			// The same thing over and over gets boring; something new is a relief.
			c.mood().bump(Mood.Kind.BOREDOM, g.key().equals(previousGoal) ? 0.12F : -0.15F, now);
			previousGoal = g.key();
		}
		return g;
	}

	@Nullable
	private Goal chooseFreeRules(Companion c, long now) {
		CompanionPlayer p = c.player();
		PlayerInventory inv = p.getInventory();
		BlockPos home = home(c);
		String h = home.getX() + " " + home.getY() + " " + home.getZ();
		double fromHome = Math.sqrt(p.getBlockPos().getSquaredDistance(home));

		// 1. Night: sleep if that skips the night (someone else is sleeping, or there's nobody else
		// to wait for); otherwise carry on with work close to home, carefully: the base's upkeep,
		// the farm, the night chores, mining from home. Unarmed, arm up or dig in.
		if (c.perception().isNight()) {
			boolean rest = restTonight(c);
			if (rest && allowed("sleep", now)) {
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
				// Home for the night: the chores that need nothing from outside, then work close by.
				Goal chores = nightChores(c, inv, now);
				if (chores != null) {
					return chores;
				}
				Goal work = nightWork(c, inv, now);
				if (work != null) {
					return work;
				}
				if (!rest && allowed("sleep", now) && bedNear(c, home)) {
					return new Goal("sleep", List.of("sleep"), "nothing left to do tonight");
				}
				return null;
			}
			// Unarmed at home with wood in reach: a sword first (that's what keeps us working at night).
			var base = com.botcraft.colony.Colony.get();
			if (base != null && base.hasBase() && base.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS)
				&& allowed("sword", now) && planksAvailable(c, inv) >= 3) {
				return new Goal("sword", List.of("craft wooden_sword 1"), "I need a weapon before I go out in the dark");
			}
			if (allowed("shelter", now)) {
				return new Goal("shelter", List.of("shelter"), "I'm not facing the night without a sword");
			}
			return null;
		}
		// A faction without a base (teams and dynamic factions): the founder picks a spot and settles it.
		Goal found = foundGoal(c, now);
		if (found != null) {
			return found;
		}
		List<Goal> goals = new ArrayList<>();
		// Storage: put things away when we're at the base anyway, or come back when our pockets are full.
		Goal store = storeGoal(c, inv, now);
		if (store != null) {
			goals.add(store);
		}
		// A new base in a wood: clear it first, all hands (the trees are spread between whoever
		// comes; the logs go in the chests). That's what makes room for the farm and pen and makes
		// it look like a home.
		var baseC = com.botcraft.colony.Colony.get();
		if (baseC != null && com.botcraft.colony.Upkeep.unsettled(baseC) && allowed("clear_home", now)
			&& com.botcraft.colony.Upkeep.count(baseC, com.botcraft.colony.Upkeep.Kind.FELL) > 0
			&& baseC.world() == p.getServerWorld() && baseC.near(p.getServerWorld(), p.getBlockPos(), 96)) {
			goals.add(new Goal("clear_home", List.of("gather logs 64 home", "home", "deposit"), "the base needs clearing"));
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
		// The farm: harvest what's ripe, replant, till (food that doesn't run away). Wheat makes bread.
		Goal farm = farmGoal(c, inv, now);
		if (farm != null) {
			goals.add(farm);
		}
		int wheat = Inventories.count(inv, s -> s.isOf(Items.WHEAT));
		if (wheat >= 3 && food < 8 && allowed("bread", now)) {
			goals.add(new Goal("bread", List.of("craft bread " + Math.min(8, wheat / 3)), "I've got wheat for bread"));
		}
		// Better armour, tools or weapons lying in the chests (someone made spares, or died and
		// got them back): wear and carry the best there is before making anything new.
		List<String> spare = Jobs.gearFromStock(c, null, true);
		if (!spare.isEmpty()) {
			goals.add(new Goal("gear_from_stock", spare, "there's better gear in the chests"));
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
				String id = com.botcraft.knowledge.ItemNames.id(inv.getStack(carried).getItem());
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
				boardOrdered = false;
				return new Goal("board", a.steps(), a.reason());
			}
		}
		// Progression: the next target on the ladder (iron tools, iron armour, diamonds...), got
		// whichever way the planner finds cheapest right now.
		// (Planned only when nothing more urgent is waiting: planning isn't free.)
		// (Always on offer, so getting armed never waits for a quiet moment that doesn't come;
		// re-planned at most every 30 seconds, since planning isn't free.)
		if (stonePick) {
			Goal target = cachedTarget(c, now);
			if (target != null) {
				goals.add(target);
			}
		}
		// 5. Whatever suits where we are (like a player would): wood in a forest, ore in a cave.
		boolean underground = !com.botcraft.nav.Terrain.openSky(p.getWorld(), p.getBlockPos().up());
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
			var stockC = com.botcraft.colony.Colony.get();
			if (stockC == null || stockC.count(Inventories::isLog) < 64) {
				goals.add(new Goal("forest_logs", List.of("gather logs 16"), "lots of trees here, good time to stock up on wood"));
			}
		}
		if (coal > 0 && Inventories.count(inv, s -> s.isOf(Items.TORCH)) < 16) {
			goals.add(new Goal("torches", List.of("craft torch 8"), "torches for the dark"));
		}
		// Keeping the base supplied when nothing else is pressing: wood, coal and iron are what
		// everything else is made of. (Last on offer, so they fill gaps rather than crowd out.)
		var stockC = com.botcraft.colony.Colony.get();
		if (stockC != null && stockC.hasBase()) {
			int logs = stockC.count(Inventories::isLog) + Inventories.count(inv, Inventories::isLog);
			int coalAll = stockC.count(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) + coal;
			int ironAll = stockC.count(s -> s.isOf(Items.IRON_INGOT) || s.isOf(Items.RAW_IRON)) + iron;
			if (logs < 64) {
				goals.add(new Goal("stock_logs", List.of("gather logs 16", "home", "deposit"), "the base could use more wood"));
			}
			if (stonePick && coalAll < 32) {
				goals.add(new Goal("stock_coal", List.of("mine coal 8", "home", "deposit"), "the base is low on coal"));
			}
			if (stonePick && ironAll < 24) {
				goals.add(new Goal("stock_iron", List.of("mine iron 6", "home", "deposit"), "we need iron to get properly armed"));
			}
		}
		Goal fish = fishGoal(c, inv, food + raw, now);
		if (fish != null) {
			goals.add(fish);
		}
		// Last and least: a wolf in sight and bones to hand (or in the chests) -> try taming it.
		Goal tame = tameGoal(c, inv, now);
		if (tame != null) {
			goals.add(tame);
		}
		// Everything that makes sense now goes on offer, in the old order of importance; the tree
		// weighs them with this companion's character (and sticks with what it's already doing).
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < goals.size(); i++) {
			Goal g = goals.get(i);
			if (seen.add(g.key())) {
				tree.offer(com.botcraft.brain.goal.GoalTree.rootOf(g.key()), g.key(), g.reason(), g.jobs(), 1.0F - i * 0.04F);
			}
		}
		var best = tree.pick(now, weights(c), c.brain().personality().commitment(), !com.botcraft.util.Experiments.on("goalUtility"));
		if (best != null) {
			picked = true;
			return new Goal(best.key, best.jobs, best.reason);
		}
		// 6. Nothing pressing: go have a look at a cave we know, or wander (curious ones more).
		if (allowed("explore", now) && random.nextFloat() < 0.3F + c.data.curiosity * 0.5F) {
			tree.rest("explore", now + 20 * 60); // don't wander off every few seconds
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
		var colony = com.botcraft.colony.Colony.get();
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

	/** Per faction base: when its dark spots were last counted, and how many there were. */
	private static final Map<String, long[]> DARK_AT_BASE = new HashMap<>();

	/** The base has dark ground and we have (or can make) torches: light it up. */
	@Nullable
	private Goal lightGoal(Companion c, PlayerInventory inv, long now) {
		var colony = com.botcraft.colony.Colony.get();
		var p = c.player();
		if (!CompanionConfig.get().placeTorches || colony == null || !colony.hasBase() || !allowed("light_home", now)
			|| !colony.near(p.getServerWorld(), p.getBlockPos(), 48)) {
			return null;
		}
		long[] dark = DARK_AT_BASE.computeIfAbsent(colony.id(), k -> new long[]{-10_000, 0});
		if (now - dark[0] > 20 * 30) { // shared by everyone at this base, refreshed every 30 s
			dark[0] = now;
			dark[1] = com.botcraft.brain.Lighting.darkSpots(colony.world(), colony.base(),
				com.botcraft.brain.task.LightHomeTask.radius(colony)).size();
		}
		if (dark[1] == 0) {
			return null;
		}
		int torches = Inventories.count(inv, s -> s.isOf(Items.TORCH));
		if (torches >= 4) {
			return new Goal("light_home", List.of("light"), "it's dark around the base");
		}
		java.util.Map<net.minecraft.item.Item, Integer> have = new java.util.HashMap<>(com.botcraft.brain.task.CraftTask.inventoryCounts(inv));
		colony.stock().forEach((item, n) -> have.merge(item, n, Integer::sum));
		if (com.botcraft.knowledge.CraftPlanner.plan(c.server, have, Items.TORCH, 16).ready()) {
			return new Goal("light_home", List.of("craft torch 16", "light"), "it's dark around the base, I'll make torches");
		}
		// No coal yet (a new base): dig some up if we can, else burn logs into charcoal. A dark base
		// is where the night's monsters come from, so this comes early, not after the iron.
		boolean pickaxe = has(inv, s -> s.getItem() instanceof PickaxeItem);
		int logs = have.entrySet().stream().filter(e -> new net.minecraft.item.ItemStack(e.getKey()).isIn(ItemTags.LOGS))
			.mapToInt(Map.Entry::getValue).sum();
		if (pickaxe) {
			return new Goal("light_home", List.of("mine coal 4", "craft torch 16", "light"), "it's dark around the base, I'll get coal for torches");
		}
		if (logs >= 6) {
			return new Goal("light_home", List.of("smelt charcoal 4", "craft torch 16", "light"), "it's dark around the base, I'll burn some charcoal for torches");
		}
		return null;
	}

	/**
	 * Tend the base's farm when there's something to do there: ripe crops, or empty soil and
	 * seeds to plant (pulling up grass for seeds when we have none). Only near home, in daylight.
	 */
	@Nullable
	private Goal farmGoal(Companion c, PlayerInventory inv, long now) {
		var colony = com.botcraft.colony.Colony.get();
		var p = c.player();
		if (!CompanionConfig.get().farming || !com.botcraft.util.Experiments.on("farming") || colony == null || !allowed("farm", now) || colony.world() != p.getServerWorld()
			|| !colony.near(p.getServerWorld(), p.getBlockPos(), 64)) {
			return null;
		}
		if (colony.zone(com.botcraft.colony.Zones.FARM) == null) {
			return null;
		}
		long[] seen = FARM_SEEN.computeIfAbsent(colony.id(), k -> new long[]{-10_000, 0, 0, 0, 0, 0, 0});
		if (now - seen[0] > 20 * 20) { // shared by everyone at this base, refreshed every 20 s
			var n = com.botcraft.brain.task.FarmTask.needs(p.getServerWorld(), colony);
			seen[0] = now;
			seen[1] = n.ripe();
			seen[2] = n.empty();
			seen[3] = n.tillable();
			seen[4] = n.grass();
			seen[5] = n.dry();
			seen[6] = n.wild();
		}
		var needs = new com.botcraft.brain.task.FarmTask.Needs((int) seen[1], (int) seen[2], (int) seen[3], (int) seen[4], (int) seen[5], (int) seen[6]);
		// Seeds in the chests count as ours (the farm job fetches them first).
		boolean seeds = com.botcraft.brain.task.FarmTask.seeds(p) > 0 || com.botcraft.brain.task.FarmTask.seedsInStock(colony);
		boolean hoe = com.botcraft.brain.task.FarmTask.hasHoe(p);
		boolean hungry = Inventories.foodCount(inv) == 0 && p.getHungerManager().getFoodLevel() < 14;
		boolean water = needs.dry() > 0 && com.botcraft.brain.task.FarmTask.hasBucket(p);
		if (!needs.worthIt(seeds, hoe, hungry) && !water) {
			return null;
		}
		// One farmer per base at a time (everyone else would just get in each other's way).
		if (com.botcraft.brain.task.FarmTask.someoneElseFarming(colony.id(), c.name(), now)) {
			return null;
		}
		String why = needs.ripe() > 0 ? "the crops are ripe" : water ? "the farm needs water" : seeds ? "the farm needs planting" : "I need seeds for the farm";
		return new Goal("farm", List.of("farm"), why);
	}

	/**
	 * Our faction has no base yet: if we're its founder (the most ambitious member living on its
	 * own), survey the area and go settle the best spot. Not in single mode, where the owner says
	 * where home is.
	 */
	@Nullable
	private Goal foundGoal(Companion c, long now) {
		if (!com.botcraft.faction.Factions.enabled() || !CompanionConfig.get().dynamicBases || !com.botcraft.util.Experiments.on("dynamicBases")
			|| !allowed("found_base", now)) {
			return null;
		}
		var colony = com.botcraft.colony.Colony.get();
		if (colony == null || colony.hasBase() || !isFounder(c)) {
			return null;
		}
		var site = com.botcraft.colony.SiteSurvey.choose(c);
		if (site == null) {
			// Nowhere good around here (too near the others, or poor ground): head off away from the
			// nearest base and look again there.
			var p = c.player();
			if (p == null) {
				return null;
			}
			BlockPos to = com.botcraft.colony.SiteSurvey.scoutFrom(p.getServerWorld(), p.getBlockPos(), com.botcraft.faction.Factions.idOf(c), 96,
				new java.util.Random(now ^ c.data.uuid.getLeastSignificantBits()));
			return new Goal("found_base", List.of("goto " + to.getX() + " " + to.getY() + " " + to.getZ() + " to look for a place to settle"),
				"we need a base, somewhere with room");
		}
		BlockPos s = site.pos();
		return new Goal("found_base", List.of("goto " + s.getX() + " " + s.getY() + " " + s.getZ() + " to our new home", "found"),
			"we need a base (" + site.why() + ")");
	}

	/** The member of our faction who founds its base: its leader, else the most ambitious one living on its own. */
	private static boolean isFounder(Companion c) {
		Companion best = null;
		double bestScore = -1;
		var faction = com.botcraft.faction.Factions.of(c);
		for (Companion o : com.botcraft.faction.Factions.members(faction.id)) {
			if (!o.isOnline() || !"free".equals(o.data.mode)) {
				continue;
			}
			Personality p = o.brain().personality();
			double score = p.ambition() + 0.3 * p.sociability()
				+ (faction.rank(o.data.uuid.toString()) == com.botcraft.faction.Faction.Rank.LEADER ? 10 : 0);
			if (best == null || score > bestScore || (score == bestScore && o.name().compareTo(best.name()) < 0)) {
				best = o;
				bestScore = score;
			}
		}
		return best == c;
	}

	/**
	 * Go fishing: we know of water near here, we've a rod or the makings of one (three sticks, or
	 * wood, and two string), and either food is short or we're the curious sort (explorers and
	 * scouts like a quiet hour by the water). In daylight.
	 */
	@Nullable
	private Goal fishGoal(Companion c, PlayerInventory inv, int food, long now) {
		var p = c.player();
		if (!CompanionConfig.get().fishing || !com.botcraft.util.Experiments.on("fishing") || !allowed("fish", now)
			|| !p.getWorld().isDay() || c.data.curiosity < 0.35F && food >= 6) {
			return null;
		}
		var water = c.memory().sightings("water", p.getBlockPos(), c.dimension(), 64);
		if (water.isEmpty()) {
			return null;
		}
		boolean rod = Inventories.count(inv, s -> s.isOf(Items.FISHING_ROD)) > 0;
		if (!rod) {
			var colony = com.botcraft.colony.Colony.get();
			int string = Inventories.count(inv, s -> s.isOf(Items.STRING)) + (colony == null ? 0 : colony.count(s -> s.isOf(Items.STRING)));
			boolean wood = Inventories.count(inv, s -> s.isOf(Items.STICK) || s.isIn(ItemTags.PLANKS) || s.isIn(ItemTags.LOGS)) > 0;
			if (string < 2 || !wood) {
				return null;
			}
		}
		int n = food < 6 ? 6 : 3 + Math.round(c.data.curiosity * 4);
		return new Goal("fish", List.of("fish " + n), food < 6 ? "there's fish in that water, and we're short of food" : "I know a good spot for fishing");
	}

	/** Wolves a companion keeps at most (any more and it'd be leading a pack about). */
	private static final int MAX_WOLVES = 2;

	/**
	 * Tame a wolf: one's in sight (untamed, not angry, within 24 blocks), and we've bones, or the
	 * chests have some and the base is close enough to fetch them. Low priority: offered last, it
	 * only gets picked when nothing much else is pressing, and never cuts into a job (life goals
	 * are only chosen between jobs). Not with two wolves of our own already.
	 */
	@Nullable
	private Goal tameGoal(Companion c, PlayerInventory inv, long now) {
		var p = c.player();
		if (!CompanionConfig.get().taming || !com.botcraft.util.Experiments.on("taming") || !allowed("tame", now) || p.getServerWorld().isNight()) {
			return null;
		}
		var wolf = com.botcraft.brain.task.TameTask.nearestWild(p, 24);
		if (wolf == null || !p.canSee(wolf) || com.botcraft.brain.task.TameTask.owned(p) >= MAX_WOLVES) {
			return null;
		}
		if (Inventories.count(inv, com.botcraft.brain.task.TameTask.BONE) > 0) {
			return new Goal("tame", List.of("tame"), "there's a wolf, and I've bones for it");
		}
		String fetch = Jobs.withdrawSpec(c, "bone", com.botcraft.brain.task.TameTask.BONE, 8);
		return fetch == null ? null : new Goal("tame", List.of(fetch, "tame"), "there's a wolf about, and bones in the chests");
	}

	/** Per base: when the farm was last looked at, and ripe/empty/tillable/grass counts. */
	private static final Map<String, long[]> FARM_SEEN = new HashMap<>();

	/** A mining job started at the base at night means walking out into the dark to a cave. */
	private static boolean leavingBaseToMine(Companion c) {
		var colony = com.botcraft.colony.Colony.get();
		var p = c.player();
		return c.brain().job() instanceof com.botcraft.brain.task.MineTask && colony != null
			&& colony.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS)
			&& com.botcraft.nav.Terrain.openSky(p.getServerWorld(), p.getBlockPos().up());
	}

	/** Put things in the base chests: when we're there with plenty to store, or when we're nearly full. */
	@Nullable
	private Goal storeGoal(Companion c, PlayerInventory inv, long now) {
		var colony = com.botcraft.colony.Colony.get();
		var p = c.player();
		if (colony == null || !colony.hasBase() || colony.world() != p.getServerWorld() || !allowed("deposit", now)) {
			return null;
		}
		if (!chestPossible(c, inv)) {
			return null; // no chest and no wood for one: chopping comes first (another goal)
		}
		int toStore = com.botcraft.colony.Kit.storeSlots(inv);
		int empty = com.botcraft.colony.Kit.emptySlots(inv);
		boolean atBase = colony.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS);
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
		var colony = com.botcraft.colony.Colony.get();
		var p = c.player();
		if (colony == null || !colony.hasBase() || !colony.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS)) {
			return null;
		}
		if (com.botcraft.colony.Kit.storeSlots(inv) > 0 && allowed("deposit", now) && chestPossible(c, inv)) {
			return new Goal("deposit", List.of("deposit"), "might as well tidy up");
		}
		Goal light = lightGoal(c, inv, now);
		if (light != null) {
			return light;
		}
		java.util.Map<net.minecraft.item.Item, Integer> have = new java.util.HashMap<>(com.botcraft.brain.task.CraftTask.inventoryCounts(inv));
		colony.stock().forEach((item, n) -> have.merge(item, n, Integer::sum));
		int raw = have.entrySet().stream().filter(e -> Animals.RAW_MEAT.contains(e.getKey())).mapToInt(java.util.Map.Entry::getValue).sum();
		boolean fuel = have.getOrDefault(Items.COAL, 0) + have.getOrDefault(Items.CHARCOAL, 0) > 0
			|| have.entrySet().stream().anyMatch(e -> new net.minecraft.item.ItemStack(e.getKey()).isIn(net.minecraft.registry.tag.ItemTags.LOGS)
			|| new net.minecraft.item.ItemStack(e.getKey()).isIn(net.minecraft.registry.tag.ItemTags.PLANKS));
		if (raw > 0 && fuel && Inventories.foodCount(inv) - Inventories.count(inv, s -> Animals.RAW_MEAT.contains(s.getItem())) < com.botcraft.colony.Kit.FOOD
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
			String id = com.botcraft.knowledge.ItemNames.id(gear);
			if (allowed("night_" + id, now) && com.botcraft.knowledge.CraftPlanner.plan(c.server, have, gear, 1).ready()) {
				return new Goal("night_" + id, List.of("craft " + id + " 1"), "there's enough iron in the chests for this");
			}
		}
		return null;
	}

	/**
	 * Sleep tonight? Only when that gets the night skipped: a real player is in bed (or there's no
	 * real player here to wait for), or night work is turned off. Otherwise lying in bed is just
	 * lying there until morning.
	 */
	private static boolean restTonight(Companion c) {
		if (!CompanionConfig.get().nightWork) {
			return true;
		}
		boolean anyone = false;
		for (var o : c.player().getServerWorld().getPlayers()) {
			if (o instanceof com.botcraft.entity.CompanionPlayer || o.isSpectator()) {
				continue;
			}
			anyone = true;
			if (o.isSleeping()) {
				return true;
			}
		}
		return !anyone;
	}

	/**
	 * Work at night, close to home (armed, careful, among the others): the base's upkeep from the
	 * board (clearing, levelling, filling, fencing), the farm, and mining from home for what the
	 * base is short of.
	 */
	@Nullable
	private Goal nightWork(Companion c, PlayerInventory inv, long now) {
		var colony = com.botcraft.colony.Colony.get();
		var p = c.player();
		if (!CompanionConfig.get().nightWork || colony == null || !colony.hasBase()
			|| !colony.near(p.getServerWorld(), p.getBlockPos(), com.botcraft.colony.Colony.BASE_RADIUS)) {
			return null;
		}
		if (!boardRest) {
			Board.Assignment a = Board.takeAtNight(c, now);
			if (a != null) {
				boardRequest = a.request().id;
				boardHolder = c.name();
				boardOrdered = false;
				return new Goal("board", a.steps(), a.reason());
			}
		}
		Goal farm = farmGoal(c, inv, now);
		if (farm != null) {
			return farm;
		}
		boolean stonePick = has(inv, s -> s.getItem() instanceof PickaxeItem && !s.isOf(Items.WOODEN_PICKAXE) && !s.isOf(Items.GOLDEN_PICKAXE));
		if (stonePick && allowed("night_mine", now)) {
			int coal = colony.count(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) + Inventories.count(inv, s -> s.isOf(Items.COAL));
			int iron = colony.count(s -> s.isOf(Items.IRON_INGOT) || s.isOf(Items.RAW_IRON))
				+ Inventories.count(inv, s -> s.isOf(Items.IRON_INGOT) || s.isOf(Items.RAW_IRON));
			int stone = colony.count(s -> s.isOf(Items.COBBLESTONE));
			String job = coal < 32 ? "mine coal 8" : iron < 32 ? "mine iron 6" : stone < 64 ? "mine stone 16" : null;
			if (job != null) {
				return new Goal("night_mine", List.of(job, "home", "deposit"), "a good night for some mining near home");
			}
		}
		return null;
	}

	private static boolean wearing(com.botcraft.entity.CompanionPlayer p, net.minecraft.item.Item item) {
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
			.map(e -> com.botcraft.knowledge.ItemNames.id(e.getKey())).orElse("beef");
	}

	/** Self-chosen jobs that keep a companion outdoors and away from home after dark. */
	private static boolean outdoorErrand(@Nullable Task job) {
		// (The farm and clearing home's trees are at home: fine at night.)
		return job instanceof com.botcraft.brain.task.HuntTask
			|| (job instanceof com.botcraft.brain.task.GatherLogsTask gl && !gl.clearingHome())
			|| job instanceof com.botcraft.brain.task.FishTask
			|| (job instanceof com.botcraft.brain.task.GoToTask g && (g.label().equals("exploring") || g.label().equals("scouting")));
	}

	/** The last planning decision, for /companion why. */
	@Nullable
	private com.botcraft.brain.plan.Planner.Decision lastDecision;
	@Nullable
	private String lastTarget;

	@Nullable
	public com.botcraft.brain.plan.Planner.Decision lastDecision() {
		return lastDecision;
	}

	@Nullable
	public String lastTarget() {
		return lastTarget;
	}

	/** The next progression target and the cheapest way to reach it. */
	@Nullable
	private Goal targetGoal(Companion c, long now) {
		var target = com.botcraft.brain.plan.Targets.next(c, t -> allowed("target_" + com.botcraft.knowledge.ItemNames.id(t.item()), now));
		if (target == null) {
			return null;
		}
		var decision = com.botcraft.brain.plan.Planner.acquire(c, target.item(), 1);
		lastDecision = decision;
		lastTarget = target.tier() + ": " + target.name();
		String key = "target_" + com.botcraft.knowledge.ItemNames.id(target.item());
		if (!decision.chosen().possible() || decision.chosen().steps().isEmpty()) {
			AiLog.log(c.name(), "life: can't plan {} ({})", target.name(), decision.chosen().note());
			tree.failed(key, now, decision.chosen().note());
			return null;
		}
		AiLog.log(c.name(), "plan for {}: {} ({}s) {} | also {}", target.name(), decision.chosen().method(),
			Math.round(decision.chosen().cost()), decision.chosen().steps(), decision.considered().stream()
				.filter(o -> o != decision.chosen()).map(o -> o.method() + " " + (o.possible() ? Math.round(o.cost()) + "s" : "no")).toList());
		return new Goal(key, decision.chosen().steps(), target.tier() + " next: " + target.name());
	}

	@Nullable
	private Goal targetCache;
	private long targetAt = -10_000;

	/** {@link #targetGoal}, planned again at most every 30 seconds (or once the last one is done or put aside). */
	@Nullable
	private Goal cachedTarget(Companion c, long now) {
		if (targetCache == null || now - targetAt > 20 * 30 || !allowed(targetCache.key(), now)) {
			targetCache = targetGoal(c, now);
			targetAt = now;
		}
		return targetCache;
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
		com.botcraft.CompanionManager m = com.botcraft.CompanionManager.get();
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
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
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

	/**
	 * Storing can work without a trip outside: the base has a chest, or we (or the chests) have
	 * one, or the wood for one (8 planks). Otherwise "put things away" means "go chop a tree",
	 * which at night gets called off straight away, over and over.
	 */
	private static boolean chestPossible(Companion c, PlayerInventory inv) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		return colony != null && colony.chestCount() > 0 || has(inv, s -> s.isOf(Items.CHEST)) || planksAvailable(c, inv) >= 8;
	}

	/** Planks we could have for crafting: carried or stored, logs counting as four. */
	private static int planksAvailable(Companion c, PlayerInventory inv) {
		int n = Inventories.count(inv, s -> s.isIn(ItemTags.PLANKS)) + 4 * Inventories.count(inv, Inventories::isLog);
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
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
