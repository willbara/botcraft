package com.botcraft.entity;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Right-click a companion to open its inventory: a vanilla 5-row chest screen (so no client
 * mod is needed) backed by the companion's own inventory, so items can be taken, given and
 * moved like in a chest. Changes show up in what the companion has at once; jobs that were
 * counting on something taken find out when they next look and plan again.
 *
 * <p>Layout: rows 1-3 the main inventory, row 4 the hotbar, row 5 helmet, chestplate,
 * leggings, boots, the off hand and four locked filler slots. Armour slots only take what
 * fits them.
 */
public final class InventoryView {
	private static final int ROWS = 5;
	private static final int SIZE = ROWS * 9;
	/** Farthest a viewer may be from the companion before the screen closes. */
	private static final double VIEW_RANGE = 8.0;
	/** PlayerInventory slot for each of the 45 chest slots; -1 = locked filler. */
	private static final int[] SLOTS = new int[SIZE];
	/** Armour slot each chest slot of row 5 stands for (null = not armour). */
	private static final EquipmentSlot[] ARMOR = new EquipmentSlot[SIZE];

	static {
		for (int i = 0; i < 27; i++) {
			SLOTS[i] = 9 + i; // main inventory
		}
		for (int i = 0; i < 9; i++) {
			SLOTS[27 + i] = i; // hotbar
		}
		int[] last = {39, 38, 37, 36, 40, -1, -1, -1, -1}; // head, chest, legs, feet, off hand
		System.arraycopy(last, 0, SLOTS, 36, 9);
		ARMOR[36] = EquipmentSlot.HEAD;
		ARMOR[37] = EquipmentSlot.CHEST;
		ARMOR[38] = EquipmentSlot.LEGS;
		ARMOR[39] = EquipmentSlot.FEET;
	}

	private InventoryView() {
	}

	/** UseEntityCallback: open the inventory on a right-click (the interact packet, main hand). */
	public static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity, @Nullable EntityHitResult hit) {
		if (world.isClient() || !(entity instanceof CompanionPlayer target) || !(player instanceof ServerPlayerEntity viewer)
			|| viewer instanceof CompanionPlayer) {
			return ActionResult.PASS;
		}
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byPlayer(target);
		if (c == null) {
			return ActionResult.PASS;
		}
		// A click arrives as two packets (interact-at, then interact), each per hand: open once.
		if (hit == null && hand == Hand.MAIN_HAND) {
			if (!com.botcraft.faction.Incidents.mayOpenInventory(c, viewer)) {
				c.speech().reply("standing.no_inventory", viewer, new java.util.HashMap<>());
				return ActionResult.SUCCESS;
			}
			open(viewer, target);
		}
		return ActionResult.SUCCESS;
	}

	public static void open(ServerPlayerEntity viewer, CompanionPlayer target) {
		Mirror view = new Mirror(target);
		viewer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
			(syncId, playerInventory, p) -> new Handler(syncId, playerInventory, view, viewer),
			Text.literal(target.getGameProfile().getName() + "'s inventory")));
	}

	/**
	 * Chest-shaped screen (the client draws it as a 5-row chest) over the companion's inventory,
	 * then the viewer's own inventory and hotbar, in the order the client expects.
	 */
	public static final class Handler extends ScreenHandler {
		private final Mirror view;
		@Nullable
		private final ServerPlayerEntity viewer;
		/** Item counts per item when the screen opened: what changed is a gift or a theft (see faction.Incidents). */
		private final java.util.Map<net.minecraft.item.Item, Integer> before;

		Handler(int syncId, PlayerInventory playerInventory, Mirror view, @Nullable ServerPlayerEntity viewer) {
			super(ScreenHandlerType.GENERIC_9X5, syncId);
			this.view = view;
			this.viewer = viewer;
			this.before = view.counts();
			for (int row = 0; row < ROWS; row++) {
				for (int col = 0; col < 9; col++) {
					addSlot(new CompanionSlot(view, row * 9 + col, 8 + col * 18, 18 + row * 18));
				}
			}
			int offset = (ROWS - 4) * 18;
			for (int row = 0; row < 3; row++) {
				for (int col = 0; col < 9; col++) {
					addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + offset));
				}
			}
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInventory, col, 8 + col * 18, 161 + offset));
			}
		}

		@Override
		public boolean canUse(PlayerEntity player) {
			return view.canPlayerUse(player);
		}

		@Override
		public void onClosed(PlayerEntity player) {
			super.onClosed(player);
			if (viewer == null || player != viewer) {
				return;
			}
			java.util.Map<net.minecraft.item.Item, Integer> after = view.counts();
			int given = 0;
			int taken = 0;
			java.util.Set<net.minecraft.item.Item> items = new java.util.HashSet<>(before.keySet());
			items.addAll(after.keySet());
			for (net.minecraft.item.Item it : items) {
				int d = after.getOrDefault(it, 0) - before.getOrDefault(it, 0);
				if (d > 0) {
					given += d;
				} else {
					taken -= d;
				}
			}
			CompanionManager m = CompanionManager.get();
			Companion c = m == null ? null : m.byPlayer(view.target);
			if (c != null && (given > 0 || taken > 0)) {
				com.botcraft.faction.Incidents.inventoryClosed(c, viewer, given, taken);
			}
		}

		/** Shift-click: into the companion's main inventory and hotbar (armour if nothing else fits), or back out. */
		@Override
		public ItemStack quickMove(PlayerEntity player, int index) {
			Slot slot = slots.get(index);
			if (!slot.hasStack()) {
				return ItemStack.EMPTY;
			}
			ItemStack stack = slot.getStack();
			ItemStack before = stack.copy();
			if (index < SIZE) {
				if (!insertItem(stack, SIZE, slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!insertItem(stack, 36, 40, false) && !insertItem(stack, 0, 36, false)) {
				return ItemStack.EMPTY;
			}
			if (stack.isEmpty()) {
				slot.setStack(ItemStack.EMPTY);
			} else {
				slot.markDirty();
			}
			return before;
		}
	}

	/** A slot of the companion's side: armour slots take only what fits, filler slots nothing. */
	static final class CompanionSlot extends Slot {
		CompanionSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return inventory.isValid(getIndex(), stack);
		}

		@Override
		public int getMaxItemCount() {
			return ARMOR[getIndex()] != null ? 1 : super.getMaxItemCount();
		}

		@Override
		public boolean canTakeItems(PlayerEntity player) {
			if (SLOTS[getIndex()] < 0) {
				return false;
			}
			// Like the player's own armour slots: Curse of Binding keeps it on (except in creative).
			ItemStack s = getStack();
			return ARMOR[getIndex()] == null || s.isEmpty() || player.isCreative() || !EnchantmentHelper.hasBindingCurse(s);
		}
	}

	/** The companion's inventory in chest order. */
	static final class Mirror implements Inventory {
		final CompanionPlayer target;

		Mirror(CompanionPlayer target) {
			this.target = target;
		}

		/** How many of each item the companion has (everything, armour included). */
		java.util.Map<net.minecraft.item.Item, Integer> counts() {
			java.util.Map<net.minecraft.item.Item, Integer> out = new java.util.HashMap<>();
			PlayerInventory inv = inv();
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (!s.isEmpty()) {
					out.merge(s.getItem(), s.getCount(), Integer::sum);
				}
			}
			return out;
		}

		private PlayerInventory inv() {
			return target.getInventory();
		}

		private static int at(int slot) {
			return slot >= 0 && slot < SIZE ? SLOTS[slot] : -1;
		}

		@Override
		public int size() {
			return SIZE;
		}

		@Override
		public boolean isEmpty() {
			return inv().isEmpty();
		}

		@Override
		public ItemStack getStack(int slot) {
			int s = at(slot);
			return s < 0 ? ItemStack.EMPTY : inv().getStack(s);
		}

		@Override
		public ItemStack removeStack(int slot, int amount) {
			int s = at(slot);
			if (s < 0) {
				return ItemStack.EMPTY;
			}
			ItemStack out = Inventories.splitStack(List.of(inv().getStack(s)), 0, amount);
			if (inv().getStack(s).isEmpty()) {
				inv().setStack(s, ItemStack.EMPTY);
			}
			markDirty();
			return out;
		}

		@Override
		public ItemStack removeStack(int slot) {
			int s = at(slot);
			if (s < 0) {
				return ItemStack.EMPTY;
			}
			ItemStack out = inv().getStack(s);
			inv().setStack(s, ItemStack.EMPTY);
			markDirty();
			return out;
		}

		@Override
		public void setStack(int slot, ItemStack stack) {
			int s = at(slot);
			if (s >= 0) {
				inv().setStack(s, stack);
				markDirty();
			}
		}

		@Override
		public boolean isValid(int slot, ItemStack stack) {
			if (at(slot) < 0) {
				return false;
			}
			EquipmentSlot armor = ARMOR[slot];
			return armor == null || LivingEntity.getPreferredEquipmentSlot(stack) == armor;
		}

		@Override
		public void markDirty() {
			inv().markDirty();
		}

		@Override
		public boolean canPlayerUse(PlayerEntity player) {
			return !target.isRemoved() && target.isAlive() && target.getWorld() == player.getWorld()
				&& player.squaredDistanceTo(target) <= VIEW_RANGE * VIEW_RANGE;
		}

		@Override
		public void clear() {
		}
	}
}
