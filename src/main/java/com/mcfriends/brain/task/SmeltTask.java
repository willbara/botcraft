package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Jobs;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.Animals;
import com.mcfriends.knowledge.ItemNames;
import com.mcfriends.knowledge.Smelting;
import com.mcfriends.memory.Memory;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Smelt or cook {@code count} items in a furnace, the way a player would: find a free furnace
 * nearby (or one we remember, or place our own), put the input and fuel in, wait next to it,
 * take the results out. Missing input, fuel or a furnace become prerequisite jobs.
 *
 * <p>{@code output == null} means "cook food": cook whatever raw meat we carry.
 *
 * <p>Other people's furnaces are only used if their input and output slots are empty, so we
 * never take or mix up someone else's smelting.
 */
public class SmeltTask extends Task {
	private enum Phase { PLAN, FURNACE, LOAD, WAIT, PICKUP }

	private static final int SLOT_IN = 0;
	private static final int SLOT_FUEL = 1;
	private static final int SLOT_OUT = 2;

	@Nullable
	private final Item output;
	private final int count;
	private Phase phase = Phase.PLAN;
	private int phaseTicks;
	private SmeltingRecipe recipe;
	@Nullable
	private BlockPos furnace;
	private boolean placedFurnace;
	private int relocations;
	private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
	private int spotFails;
	private final java.util.Random random = new java.util.Random();
	private int made;
	private int waitTicks;

	public SmeltTask(Companion c, @Nullable Item output, int count) {
		super(c);
		this.output = output;
		this.count = Math.max(1, count);
	}

	@Override
	public String name() {
		return output == null ? "cook" : "smelt_" + ItemNames.id(output);
	}

	@Override
	public String describe() {
		String what = output == null ? "food" : ItemNames.display(output);
		return (output == null ? "cooking " : "smelting ") + what + " (" + made + "/" + count + ")";
	}

	public int made() {
		return made;
	}

	@Nullable
	public Item output() {
		return output;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 + count * 20 * 12;
	}

	@Override
	protected void onStart(boolean resumed) {
		phase = furnace != null && phase != Phase.PLAN ? Phase.FURNACE : Phase.PLAN;
		phaseTicks = 0;
	}

	private void setPhase(Phase p) {
		AiLog.log(c.name(), "{}: {} -> {}", name(), phase, p);
		phase = p;
		phaseTicks = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		phaseTicks++;
		return switch (phase) {
			case PLAN -> plan(p);
			case FURNACE -> furnace(p);
			case LOAD -> load(p);
			case WAIT -> waitForIt(p);
			case PICKUP -> pickup(p);
		};
	}

	@Nullable
	private SmeltingRecipe chooseRecipe(PlayerInventory inv) {
		if (output != null) {
			return Smelting.forOutput(c.server, output, inv);
		}
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (Animals.RAW_MEAT.contains(s.getItem())) {
				return Smelting.forInput(c.server, s);
			}
		}
		return null;
	}

	private Status plan(CompanionPlayer p) {
		PlayerInventory inv = p.getInventory();
		recipe = chooseRecipe(inv);
		if (recipe == null) {
			if (output == null) {
				return deferTo(List.of("hunt meat " + Math.min(count, 4)));
			}
			return fail("I don't know how to smelt " + ItemNames.display(output));
		}
		int need = count - made;
		int have = Smelting.countInput(inv, recipe);
		List<String> prereqs = new ArrayList<>();
		if (have == 0 && output != null) {
			// Several recipes/inputs can make the same thing (iron ingots from raw iron OR from ore
			// blocks): use the first input we actually know how to get.
			String spec = null;
			String firstName = null;
			for (SmeltingRecipe r : Smelting.allForOutput(c.server, output)) {
				for (ItemStack opt : Smelting.input(r).getMatchingStacks()) {
					if (firstName == null) {
						firstName = ItemNames.display(opt.getItem());
					}
					spec = Jobs.sourceSpec(c, opt.getItem(), need);
					if (spec != null) {
						recipe = r;
						break;
					}
				}
				if (spec != null) {
					break;
				}
			}
			if (spec == null) {
				return fail("I can't get " + (firstName == null ? "the ingredients" : firstName) + " to smelt");
			}
			prereqs.add(spec);
		}
		int fuelNeeded = Math.min(need, Math.max(have, 1)) * recipe.getCookTime();
		if (Smelting.fuelAvailable(inv, Smelting.input(recipe)) < fuelNeeded) {
			prereqs.add("gather logs " + Math.max(2, (int) Math.ceil(fuelNeeded / 300.0) + 1));
		}
		furnace = findFurnace(p);
		if (furnace == null && Inventories.count(inv, s -> s.isOf(Items.FURNACE)) == 0) {
			prereqs.add("craft furnace 1");
		}
		if (!prereqs.isEmpty()) {
			return deferTo(prereqs);
		}
		setPhase(Phase.FURNACE);
		return Status.RUNNING;
	}

	/** A usable furnace: free (input and output empty, or holding our own stuff). */
	@Nullable
	private BlockPos findFurnace(CompanionPlayer p) {
		BlockPos near = Spots.nearest(p, Blocks.FURNACE, 12);
		if (near != null && usable(near)) {
			return near;
		}
		Memory.Place mem = c.memory().place("furnace");
		if (mem != null && mem.dim.equals(c.dimension()) && mem.pos().getSquaredDistance(p.getBlockPos()) < 48 * 48
			&& p.getWorld().getBlockState(mem.pos()).isOf(Blocks.FURNACE) && usable(mem.pos())) {
			return mem.pos();
		}
		return null;
	}

	private boolean usable(BlockPos pos) {
		if (!(c.player().getWorld().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity f)) {
			return false;
		}
		ItemStack in = f.getStack(SLOT_IN);
		ItemStack out = f.getStack(SLOT_OUT);
		boolean inOk = in.isEmpty() || (recipe != null && Smelting.input(recipe).test(in) && pos.equals(furnace));
		boolean outOk = out.isEmpty() || (recipe != null && out.isOf(recipe.getOutput(c.server.getRegistryManager()).getItem()) && pos.equals(furnace));
		return inOk && outOk;
	}

	private Status furnace(CompanionPlayer p) {
		if (furnace != null && p.getWorld().getBlockState(furnace).isOf(Blocks.FURNACE)) {
			if (c.actions().canReachBlock(furnace)) {
				c.nav().stop();
				setPhase(Phase.LOAD);
				return Status.RUNNING;
			}
			if (phaseTicks == 1 || c.nav().state() == Navigator.State.IDLE) {
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), furnace, 3.5), false);
			}
			if (c.nav().state() == Navigator.State.FAILED || phaseTicks > 20 * 40) {
				furnace = null;
				phaseTicks = 0;
			}
			return Status.RUNNING;
		}
		BlockPos spot = Spots.placeSpot(c, badSpots);
		if (spot == null) {
			// Cramped (on a pillar, in leaves...): step out somewhere more open and try again.
			if (phaseTicks % 60 == 20 && relocations++ < 3) {
				c.nav().moveTo(com.mcfriends.nav.Goal.near(p.getBlockPos().add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4), 1.5), false);
			}
			return phaseTicks > 60 * 4 ? fail("no room to put down a furnace") : Status.RUNNING;
		}
		if (c.actions().placeBlock(spot, s -> s.isOf(Items.FURNACE))) {
			furnace = spot;
			placedFurnace = true;
			c.memory().setPlace("furnace", spot, c.dimension(), p.getWorld().getTime());
			AiLog.log(c.name(), "placed a furnace at {}", spot.toShortString());
		} else if (++spotFails % 10 == 0) {
			AiLog.log(c.name(), "can't place a furnace at {} ({}), trying elsewhere", spot.toShortString(), p.getWorld().getBlockState(spot).getBlock());
			badSpots.add(spot);
			if (badSpots.size() > 6) {
				return fail("couldn't place a furnace");
			}
		}
		return Status.RUNNING;
	}

	@Nullable
	private AbstractFurnaceBlockEntity furnaceEntity(CompanionPlayer p) {
		return furnace != null && p.getWorld().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f ? f : null;
	}

	private Status load(CompanionPlayer p) {
		AbstractFurnaceBlockEntity f = furnaceEntity(p);
		if (f == null) {
			setPhase(Phase.FURNACE);
			return Status.RUNNING;
		}
		c.actions().lookAt(Vec3d.ofCenter(furnace));
		if (phaseTicks < 6) {
			return Status.RUNNING; // "opening" the furnace
		}
		PlayerInventory inv = p.getInventory();
		Ingredient in = Smelting.input(recipe);
		int want = count - made - f.getStack(SLOT_IN).getCount();
		moveInto(inv, f, SLOT_IN, in::test, want);
		int burnNeeded = f.getStack(SLOT_IN).getCount() * recipe.getCookTime();
		int fuelIn = Smelting.fuelTicks(f.getStack(SLOT_FUEL).getItem()) * f.getStack(SLOT_FUEL).getCount();
		if (fuelIn < burnNeeded) {
			// Best fuel first: coal/charcoal, then logs/planks; never the thing we're smelting.
			ItemStack fuelStack = f.getStack(SLOT_FUEL);
			Item fuelItem = fuelStack.isEmpty() ? bestFuel(inv, in) : fuelStack.getItem();
			if (fuelItem != null) {
				int per = Smelting.fuelTicks(fuelItem);
				int items = (int) Math.ceil((burnNeeded - fuelIn) / (double) per);
				moveInto(inv, f, SLOT_FUEL, s -> s.isOf(fuelItem), items);
			}
		}
		p.swingHand(Hand.MAIN_HAND);
		f.markDirty();
		c.perception().invalidateInventory();
		if (f.getStack(SLOT_IN).isEmpty()) {
			return made > 0 ? Status.SUCCESS : fail("I ran out of things to smelt");
		}
		waitTicks = 0;
		setPhase(Phase.WAIT);
		return Status.RUNNING;
	}

	@Nullable
	private static Item bestFuel(PlayerInventory inv, Ingredient except) {
		Item best = null;
		int bestTicks = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			int t = Smelting.fuelTicks(s.getItem());
			if (!s.isEmpty() && !except.test(s) && !s.isDamageable() && t > bestTicks && !s.isOf(Items.LAVA_BUCKET)) {
				bestTicks = t;
				best = s.getItem();
			}
		}
		return best;
	}

	private static void moveInto(PlayerInventory inv, AbstractFurnaceBlockEntity f, int slot, java.util.function.Predicate<ItemStack> which, int max) {
		for (int i = 0; i < inv.main.size() && max > 0; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !which.test(s)) {
				continue;
			}
			ItemStack cur = f.getStack(slot);
			if (!cur.isEmpty() && !ItemStack.canCombine(cur, s)) {
				return;
			}
			int room = (cur.isEmpty() ? s.getMaxCount() : cur.getMaxCount() - cur.getCount());
			int n = Math.min(Math.min(room, max), s.getCount());
			if (n <= 0) {
				return;
			}
			ItemStack moved = s.split(n);
			if (cur.isEmpty()) {
				f.setStack(slot, moved);
			} else {
				cur.increment(n);
			}
			max -= n;
		}
	}

	private Status waitForIt(CompanionPlayer p) {
		AbstractFurnaceBlockEntity f = furnaceEntity(p);
		if (f == null) {
			return made > 0 ? Status.SUCCESS : fail("the furnace is gone");
		}
		if (++waitTicks % 30 == 0) {
			c.actions().lookAt(Vec3d.ofCenter(furnace));
		}
		if (!c.actions().canReachBlock(furnace)) {
			c.nav().moveTo(Goal.reachVisible(p.getWorld(), furnace, 3.5), false);
			return Status.RUNNING;
		}
		ItemStack out = f.getStack(SLOT_OUT);
		if (!out.isEmpty() && waitTicks % 20 == 0) {
			ItemStack taken = out.copy();
			f.setStack(SLOT_OUT, ItemStack.EMPTY);
			made += taken.getCount();
			if (!p.getInventory().insertStack(taken) && !taken.isEmpty()) {
				p.dropItem(taken, false);
			}
			p.swingHand(Hand.MAIN_HAND);
			f.dropExperienceForRecipesUsed(p);
			c.perception().invalidateInventory();
			c.memory().count("smelted", 1);
		}
		boolean inputLeft = !f.getStack(SLOT_IN).isEmpty();
		if (made >= count || (!inputLeft && f.getStack(SLOT_OUT).isEmpty())) {
			if (made < count && Smelting.countInput(p.getInventory(), recipe) > 0) {
				setPhase(Phase.LOAD); // more to put in
				return Status.RUNNING;
			}
			if (placedFurnace && Inventories.bestPickaxe(p.getInventory()) != null) {
				setPhase(Phase.PICKUP);
				return Status.RUNNING;
			}
			return made > 0 ? Status.SUCCESS : fail("nothing came out");
		}
		if (inputLeft && f.getStack(SLOT_FUEL).isEmpty() && !isBurning(f) && waitTicks % 40 == 0) {
			setPhase(Phase.LOAD); // ran out of fuel: top it up
		}
		return Status.RUNNING;
	}

	private static boolean isBurning(AbstractFurnaceBlockEntity f) {
		return f.getCachedState().contains(net.minecraft.state.property.Properties.LIT) && f.getCachedState().get(net.minecraft.state.property.Properties.LIT);
	}

	private Status pickup(CompanionPlayer p) {
		if (furnace == null || !p.getWorld().getBlockState(furnace).isOf(Blocks.FURNACE)) {
			c.memory().forgetPlace("furnace");
			return phaseTicks > 40 || Inventories.count(p.getInventory(), s -> s.isOf(Items.FURNACE)) > 0 ? Status.SUCCESS : Status.RUNNING;
		}
		AbstractFurnaceBlockEntity f = furnaceEntity(p);
		if (f != null && !f.getStack(SLOT_FUEL).isEmpty()) {
			// Take leftover fuel back first so it isn't dropped on the ground.
			ItemStack fuelLeft = f.getStack(SLOT_FUEL).copy();
			f.setStack(SLOT_FUEL, ItemStack.EMPTY);
			p.getInventory().insertStack(fuelLeft);
		}
		if (c.actions().mineBlock(furnace) == ActionController.MineResult.FAILED || phaseTicks > 20 * 15) {
			return Status.SUCCESS;
		}
		return Status.RUNNING;
	}
}
