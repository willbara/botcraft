package com.botcraft.action;

import com.botcraft.Companion;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Random;
import java.util.function.Predicate;

/**
 * The companion's "hands and feet". Higher layers set intentions (look here, walk there, mine
 * that) and {@link #tick()} turns them into the same player inputs a client would produce:
 * movement input, jumping, sprinting, rotation, and ServerPlayerInteractionManager calls.
 *
 * <p>Human-like touches: rotation is eased over several ticks, aim has a small wandering
 * error, and new targets get a short reaction delay before the first attack.
 */
public class ActionController {
	/** Max reach we allow ourselves for blocks (vanilla survival client reach is 4.5). */
	public static final double BLOCK_REACH = 4.5;
	/** Melee reach (vanilla survival is 3.0). */
	public static final double ATTACK_REACH = 3.0;
	private static final float MAX_TURN_PER_TICK = 40.0F;

	private final Companion companion;
	private final Random random = new Random();

	// --- look ---
	@Nullable
	private Vec3d lookPoint;
	@Nullable
	private Entity lookEntity;
	private float jitterYaw;
	private float jitterPitch;
	private int jitterTimer;

	// --- movement ---
	@Nullable
	private Vec3d moveTarget;
	private double moveSpeed = 1.0;
	private boolean sprint;
	/** This tick's sprint may be a swim under water (otherwise no sprinting in water). */
	private boolean diveOk;
	private boolean sneak;
	private boolean jumpOnce;
	private boolean holdJump;

	// --- combat ---
	@Nullable
	private Entity lastAttackTarget;
	private int reactionTicks;

	// --- mining ---
	@Nullable
	private BlockPos miningPos;
	private float miningProgress;
	private int miningTicks;
	private int sequence = 1;

	public ActionController(Companion companion) {
		this.companion = companion;
	}

	private CompanionPlayer player() {
		return companion.player();
	}

	// =========================================================================================
	// Intentions (call every tick you want them; movement and look are cleared each tick)
	// =========================================================================================

	/** Look at a point this tick. */
	public void lookAt(Vec3d point) {
		this.lookPoint = point;
		this.lookEntity = null;
	}

	/** Look at an entity's eyes (or body for small mobs) this tick. */
	public void lookAt(Entity entity) {
		this.lookEntity = entity;
		this.lookPoint = null;
	}

	/** Walk toward a point this tick (y is used only for jump decisions by the navigator). */
	public void moveTo(Vec3d target, double speed) {
		this.moveTarget = target;
		this.moveSpeed = MathHelper.clamp(speed, 0.0, 1.0);
	}

	public void moveTo(Vec3d target) {
		moveTo(target, 1.0);
	}

	public void sprint(boolean sprint) {
		this.sprint = sprint;
	}

	/**
	 * Sprint even in water. Sprinting in water is the swimming pose, head under: right for a
	 * route that goes under water, wrong anywhere else (it drowns you crossing a lake), so plain
	 * {@link #sprint(boolean)} doesn't sprint in water.
	 */
	public void sprintSwim(boolean sprint) {
		this.sprint = sprint;
		this.diveOk = sprint;
	}

	public void sneak(boolean sneak) {
		this.sneak = sneak;
	}

	/** Jumps asked for so far (the nav bench and tests count them: hopping in place is a known failure). */
	public int jumps;

	/** Jump once (when on ground). */
	public void jump() {
		if (!jumpOnce) {
			jumps++;
		}
		this.jumpOnce = true;
	}

	/** Keep the jump key down this tick (swimming up, climbing). */
	public void holdJump() {
		this.holdJump = true;
	}

	public boolean isMoving() {
		return moveTarget != null;
	}

	/** Stop everything: movement, mining, item use. */
	public void reset() {
		moveTarget = null;
		lookPoint = null;
		lookEntity = null;
		sprint = false;
		diveOk = false;
		sneak = false;
		holdJump = false;
		jumpOnce = false;
		abortMining();
		CompanionPlayer p = player();
		if (p != null) {
			p.forwardSpeed = 0;
			p.sidewaysSpeed = 0;
			p.setJumping(false);
			p.setSprinting(false);
			p.setSneaking(false);
			if (p.isUsingItem()) {
				p.stopUsingItem();
			}
		}
	}

	// =========================================================================================
	// Per-tick application
	// =========================================================================================

	public void tick() {
		CompanionPlayer p = player();
		if (p == null) {
			return;
		}
		if (reactionTicks > 0) {
			reactionTicks--;
		}
		applyLook(p);
		applyMovement(p);
		tickMining(p);
		// Intentions are per-tick: callers re-issue them each tick.
		moveTarget = null;
		lookPoint = null;
		lookEntity = null;
		holdJump = false;
		jumpOnce = false;
		sprint = false;
		diveOk = false;
	}

	private static final boolean LOOK_EASING = com.botcraft.util.Experiments.on("lookEasing");

	private void applyLook(CompanionPlayer p) {
		Vec3d target = null;
		if (lookEntity != null && lookEntity.isAlive()) {
			double y = lookEntity.getHeight() > 1.0 ? lookEntity.getEyeY() - 0.1 : lookEntity.getY() + lookEntity.getHeight() * 0.5;
			target = new Vec3d(lookEntity.getX(), y, lookEntity.getZ());
		} else if (lookPoint != null) {
			target = lookPoint;
		} else if (moveTarget != null) {
			// Look where we are walking, roughly at eye level.
			Vec3d d = moveTarget.subtract(p.getPos());
			if (d.horizontalLengthSquared() > 0.01) {
				target = new Vec3d(moveTarget.x, p.getEyeY() - 0.3, moveTarget.z);
			}
		}
		if (--jitterTimer <= 0) {
			jitterTimer = 8 + random.nextInt(15);
			jitterYaw = (float) (random.nextGaussian() * 1.5);
			jitterPitch = (float) (random.nextGaussian() * 1.0);
		}
		if (target == null) {
			return;
		}
		Vec3d eye = p.getEyePos();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float wantYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F + jitterYaw;
		float wantPitch = MathHelper.clamp((float) (-(MathHelper.atan2(dy, h) * (180.0 / Math.PI))) + jitterPitch, -90.0F, 90.0F);
		// Turning the head eases in over a few ticks, like a player (off: snap like Baritone's look).
		boolean easing = LOOK_EASING;
		float yaw = easing ? p.getYaw() + ease(MathHelper.wrapDegrees(wantYaw - p.getYaw())) : wantYaw;
		float pitch = easing ? p.getPitch() + ease(wantPitch - p.getPitch()) : wantPitch;
		p.setYaw(yaw);
		p.setHeadYaw(yaw);
		p.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
	}

	/** Turn a fraction of the remaining angle each tick: fast for big turns, gentle at the end. */
	private static float ease(float delta) {
		float step = Math.abs(delta) * 0.45F + 1.5F;
		step = Math.min(step, MAX_TURN_PER_TICK);
		if (Math.abs(delta) <= step) {
			return delta;
		}
		return Math.signum(delta) * step;
	}

	private void applyMovement(CompanionPlayer p) {
		float forward = 0;
		float sideways = 0;
		if (moveTarget != null) {
			double dx = moveTarget.x - p.getX();
			double dz = moveTarget.z - p.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist > 0.05) {
				// Slow down on the last half block so we don't overshoot path nodes.
				double scale = moveSpeed * Math.min(1.0, dist / 0.5);
				dx /= dist;
				dz /= dist;
				// Inverse of Entity#movementInputToVelocity: world direction -> (forward, sideways) for our yaw.
				double yawRad = p.getYaw() * (Math.PI / 180.0);
				double sin = Math.sin(yawRad);
				double cos = Math.cos(yawRad);
				forward = (float) ((-dx * sin + dz * cos) * scale);
				sideways = (float) ((dx * cos + dz * sin) * scale);
			}
		}
		p.forwardSpeed = forward;
		p.sidewaysSpeed = sideways;
		boolean canSprint = sprint && forward > 0.8F && !sneak && p.getHungerManager().getFoodLevel() > 6 && !p.isUsingItem()
			&& (diveOk || !p.isTouchingWater());
		if (p.isSprinting() != canSprint) {
			p.setSprinting(canSprint);
		}
		if (p.isSneaking() != sneak) {
			p.setSneaking(sneak);
		}
		// Never jump on farmland: landing on it tramples it back to dirt.
		if (jumpOnce && !holdJump && onFarmland(p)) {
			jumpOnce = false;
		}
		p.setJumping(holdJump || (jumpOnce && (p.isOnGround() || p.isTouchingWater())));
	}

	private static boolean onFarmland(CompanionPlayer p) {
		var w = p.getWorld();
		BlockPos at = p.getBlockPos();
		return w.getBlockState(at).isOf(net.minecraft.block.Blocks.FARMLAND) || w.getBlockState(at.down()).isOf(net.minecraft.block.Blocks.FARMLAND);
	}

	// =========================================================================================
	// Looking helpers
	// =========================================================================================

	/** Angle (degrees) between where we look and the given point. */
	public float aimError(Vec3d point) {
		CompanionPlayer p = player();
		Vec3d look = p.getRotationVec(1.0F);
		Vec3d to = point.subtract(p.getEyePos()).normalize();
		double dot = MathHelper.clamp(look.dotProduct(to), -1.0, 1.0);
		return (float) Math.toDegrees(Math.acos(dot));
	}

	/**
	 * The block in the way when the target is within reach distance but hidden, or null (not in
	 * reach at all, or visible).
	 */
	@Nullable
	public BlockPos obstruction(BlockPos pos) {
		CompanionPlayer p = player();
		Vec3d eye = p.getEyePos();
		Vec3d center = Vec3d.ofCenter(pos);
		if (eye.squaredDistanceTo(center) > BLOCK_REACH * BLOCK_REACH) {
			return null;
		}
		BlockHitResult hit = p.getWorld().raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.NONE, p));
		if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(pos)) {
			return hit.getBlockPos();
		}
		return null;
	}

	/** For logs: why canReachBlock fails. */
	public String reachDebug(BlockPos pos) {
		CompanionPlayer p = player();
		Vec3d eye = p.getEyePos();
		Vec3d center = Vec3d.ofCenter(pos);
		BlockHitResult hit = p.getWorld().raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.NONE, p));
		return String.format("eye %s dist %.2f hit %s %s", eye, Math.sqrt(eye.squaredDistanceTo(center)), hit.getType(),
			hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().toShortString() + " " + p.getWorld().getBlockState(hit.getBlockPos()).getBlock() : "");
	}

	/** Within reach and visible: like a player, we can't mine or use blocks through other blocks. */
	public boolean canReachBlock(BlockPos pos) {
		CompanionPlayer p = player();
		Vec3d eye = p.getEyePos();
		Vec3d center = Vec3d.ofCenter(pos);
		if (eye.squaredDistanceTo(center) > BLOCK_REACH * BLOCK_REACH) {
			return false;
		}
		if (sees(p, eye, center, pos)) {
			return true;
		}
		// Like a player, seeing part of it is enough: try the middle of each face too (ore set
		// into a wall often hides its centre behind the stone next to it).
		for (Direction d : Direction.values()) {
			Vec3d face = center.add(d.getOffsetX() * 0.49, d.getOffsetY() * 0.49, d.getOffsetZ() * 0.49);
			if (eye.squaredDistanceTo(face) <= BLOCK_REACH * BLOCK_REACH && sees(p, eye, face, pos)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sees(CompanionPlayer p, Vec3d eye, Vec3d point, BlockPos pos) {
		BlockHitResult hit = p.getWorld().raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.NONE, p));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	// =========================================================================================
	// Mining (survival-style, through ServerPlayerInteractionManager)
	// =========================================================================================

	/**
	 * Mine the block at pos. Call every tick until it returns DONE or FAILED. Handles looking
	 * at the block, tool selection, progress, and the START/STOP actions a client would send.
	 */
	public MineResult mineBlock(BlockPos pos) {
		CompanionPlayer p = player();
		ServerWorld world = p.getServerWorld();
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || state.getCollisionShape(world, pos).isEmpty() && !state.getFluidState().isEmpty()) {
			if (pos.equals(miningPos)) {
				miningPos = null;
			}
			return MineResult.DONE;
		}
		if (state.getHardness(world, pos) < 0) {
			return MineResult.FAILED; // bedrock, barriers, ...
		}
		if (!canReachBlock(pos)) {
			return MineResult.FAILED;
		}
		Vec3d center = Vec3d.ofCenter(pos);
		lookAt(center);
		if (!pos.equals(miningPos)) {
			abortMining();
			if (aimError(center) > 25.0F) {
				return MineResult.IN_PROGRESS; // still turning toward it
			}
			Inventories.equipBestTool(p, state);
			miningPos = pos.toImmutable();
			miningTicks = 0;
			miningProgress = state.calcBlockBreakingDelta(p, world, pos);
			p.interactionManager.processBlockBreakingAction(pos, PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, faceToward(p, pos), world.getTopY(), sequence++);
			p.swingHand(Hand.MAIN_HAND);
			return world.getBlockState(pos).isAir() ? MineResult.DONE : MineResult.IN_PROGRESS;
		}
		return MineResult.IN_PROGRESS; // progress is advanced in tickMining
	}

	private void tickMining(CompanionPlayer p) {
		if (miningPos == null) {
			return;
		}
		ServerWorld world = p.getServerWorld();
		BlockState state = world.getBlockState(miningPos);
		if (state.isAir()) {
			miningPos = null;
			return;
		}
		miningTicks++;
		p.swingHand(Hand.MAIN_HAND);
		miningProgress += state.calcBlockBreakingDelta(p, world, miningPos);
		if (miningProgress >= 1.0F) {
			p.interactionManager.processBlockBreakingAction(miningPos, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, faceToward(p, miningPos), world.getTopY(), sequence++);
			if (!world.getBlockState(miningPos).isAir()) {
				// Server disagreed (e.g. tool changed). Start over next call.
				miningPos = null;
			}
		}
		if (miningTicks > 20 * 30) {
			abortMining(); // something is wrong; let the caller retry or give up
		}
	}

	public boolean isMining() {
		return miningPos != null;
	}

	public void abortMining() {
		CompanionPlayer p = player();
		if (miningPos != null && p != null) {
			p.interactionManager.processBlockBreakingAction(miningPos, PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, p.getServerWorld().getTopY(), sequence++);
		}
		miningPos = null;
	}

	private static Direction faceToward(CompanionPlayer p, BlockPos pos) {
		Vec3d d = p.getEyePos().subtract(Vec3d.ofCenter(pos));
		return Direction.getFacing(d.x, d.y, d.z);
	}

	public enum MineResult { IN_PROGRESS, DONE, FAILED }

	// =========================================================================================
	// Using / placing blocks
	// =========================================================================================

	/** Right-click a block (doors, gates...) with whatever is in hand, without sneaking. */
	public boolean useBlock(BlockPos pos) {
		CompanionPlayer p = player();
		if (!canReachBlock(pos)) {
			return false;
		}
		Vec3d center = Vec3d.ofCenter(pos);
		Direction face = faceToward(p, pos);
		BlockHitResult hit = new BlockHitResult(center.add(Vec3d.of(face.getVector()).multiply(0.5)), face, pos, false);
		boolean wasSneaking = p.isSneaking();
		p.setSneaking(false);
		ActionResult r = p.interactionManager.interactBlock(p, p.getWorld(), p.getMainHandStack(), Hand.MAIN_HAND, hit);
		p.setSneaking(wasSneaking);
		p.swingHand(Hand.MAIN_HAND);
		return r.isAccepted();
	}

	/**
	 * Place a block from the inventory into {@code target}, clicking a solid neighbour face.
	 * Sneaks while clicking so we never open containers or toggle doors by accident.
	 */
	public boolean placeBlock(BlockPos target, Predicate<ItemStack> which) {
		CompanionPlayer p = player();
		ServerWorld world = p.getServerWorld();
		int slot = Inventories.find(p.getInventory(), which);
		if (slot < 0) {
			lastPlaceProblem = "nothing to place in the inventory";
			return false;
		}
		Placement at = placement(target);
		if (at == null) {
			return false;
		}
		Inventories.select(p, slot);
		lookAt(at.hit());
		BlockHitResult hit = new BlockHitResult(at.hit(), at.face(), at.support(), false);
		boolean wasSneaking = p.isSneaking();
		p.setSneaking(true);
		ActionResult r = p.interactionManager.interactBlock(p, world, p.getMainHandStack(), Hand.MAIN_HAND, hit);
		p.setSneaking(wasSneaking);
		if (r.isAccepted()) {
			p.swingHand(Hand.MAIN_HAND);
			boolean placed = !world.getBlockState(target).isReplaceable();
			if (!placed) {
				lastPlaceProblem = "accepted against " + at.support().toShortString() + " " + at.face() + " but nothing appeared";
			}
			return placed;
		}
		lastPlaceProblem = "refused: " + r;
		com.botcraft.util.AiLog.log(companion.name(), "place at {} against {} {} refused: {} (holding {})", target.toShortString(),
			at.support().toShortString(), at.face(), r, p.getMainHandStack());
		return false;
	}

	/** Where to click to put a block at {@code target}: a solid neighbour face within reach. */
	public record Placement(BlockPos support, Direction face, Vec3d hit) {
	}

	/** Why the last placement couldn't happen (for logs). */
	private String lastPlaceProblem = "";

	public String lastPlaceProblem() {
		return lastPlaceProblem;
	}

	/**
	 * A face we could click to place a block at {@code target}, or null (and
	 * {@link #lastPlaceProblem()} says why). Placement-spot finders use this too, so a spot
	 * they pick is one we can really place at.
	 */
	@Nullable
	public Placement placement(BlockPos target) {
		CompanionPlayer p = player();
		ServerWorld world = p.getServerWorld();
		if (!world.getBlockState(target).isReplaceable()) {
			lastPlaceProblem = "spot is taken by " + world.getBlockState(target).getBlock();
			return null;
		}
		boolean anySolid = false;
		for (Direction dir : Direction.values()) {
			BlockPos support = target.offset(dir);
			BlockState s = world.getBlockState(support);
			if (s.isAir() || s.isReplaceable() || !s.isSideSolidFullSquare(world, support, dir.getOpposite())) {
				continue;
			}
			anySolid = true;
			Vec3d hitPos = Vec3d.ofCenter(support).add(Vec3d.of(dir.getOpposite().getVector()).multiply(0.5));
			if (p.getEyePos().squaredDistanceTo(hitPos) > BLOCK_REACH * BLOCK_REACH) {
				continue;
			}
			return new Placement(support.toImmutable(), dir.getOpposite(), hitPos);
		}
		lastPlaceProblem = anySolid ? "solid side out of reach" : "nothing solid next to it";
		return null;
	}

	/**
	 * Right-click a block face with an item from the inventory (till with a hoe, plant seeds on
	 * farmland, pour a water bucket). Sneaks so the clicked block's own action never triggers.
	 */
	public boolean useItemOn(BlockPos block, Direction face, Predicate<ItemStack> which) {
		CompanionPlayer p = player();
		if (!canReachBlock(block)) {
			return false;
		}
		int slot = Inventories.find(p.getInventory(), which);
		if (slot < 0) {
			return false;
		}
		Inventories.select(p, slot);
		Vec3d hitPos = Vec3d.ofCenter(block).add(Vec3d.of(face.getVector()).multiply(0.5));
		lookAt(hitPos);
		BlockHitResult hit = new BlockHitResult(hitPos, face, block, false);
		boolean wasSneaking = p.isSneaking();
		p.setSneaking(true);
		ActionResult r = p.interactionManager.interactBlock(p, p.getServerWorld(), p.getMainHandStack(), Hand.MAIN_HAND, hit);
		p.setSneaking(wasSneaking);
		if (r.isAccepted()) {
			p.swingHand(Hand.MAIN_HAND);
		}
		return r.isAccepted();
	}

	// =========================================================================================
	// Combat
	// =========================================================================================

	/**
	 * Try to hit the target this tick. Waits for the attack cooldown, a short reaction delay
	 * after switching targets, and for our aim to be roughly on target. Occasionally whiffs.
	 *
	 * @return true if a swing happened this tick
	 */
	public boolean attack(Entity target) {
		CompanionPlayer p = player();
		lookAt(target);
		if (target != lastAttackTarget) {
			lastAttackTarget = target;
			reactionTicks = 2 + random.nextInt(4); // ~100-250 ms: a practised player's reaction
		}
		if (reactionTicks > 0) {
			return false;
		}
		if (p.squaredDistanceTo(target) > ATTACK_REACH * ATTACK_REACH) {
			return false;
		}
		if (p.getAttackCooldownProgress(0.5F) < 0.92F) {
			return false;
		}
		if (aimError(target.getBoundingBox().getCenter()) > 30.0F) {
			return false;
		}
		if (random.nextFloat() < 0.06F) {
			p.swingHand(Hand.MAIN_HAND); // a miss: swing at air (swinging resets the attack charge)
			return true;
		}
		// Hit first, then swing, like the client does: swinging resets the attack charge, and a
		// swing-then-hit lands every blow uncharged (20% damage).
		p.attack(target);
		p.swingHand(Hand.MAIN_HAND);
		return true;
	}

	// =========================================================================================
	// Items
	// =========================================================================================

	/** Start using (eating/drinking) the stack in the given inventory slot. */
	public boolean startUsing(int slot) {
		CompanionPlayer p = player();
		if (p.isUsingItem()) {
			return true;
		}
		Inventories.select(p, slot);
		ItemStack stack = p.getMainHandStack();
		ActionResult r = p.interactionManager.interactItem(p, p.getWorld(), stack, Hand.MAIN_HAND);
		return r.isAccepted() && p.isUsingItem();
	}

	/**
	 * Right-click into the air with an item, aimed at a point (fill or pour a bucket, cast or reel
	 * in a fishing rod): turns to look at it, and uses the item once the aim is good. Call every
	 * tick until it returns true.
	 */
	public boolean useItemToward(Vec3d point, Predicate<ItemStack> which) {
		CompanionPlayer p = player();
		int slot = Inventories.find(p.getInventory(), which);
		if (slot < 0) {
			return false;
		}
		Inventories.select(p, slot);
		lookAt(point);
		if (aimError(point) > 2.5F) {
			return false;
		}
		ActionResult r = p.interactionManager.interactItem(p, p.getWorld(), p.getMainHandStack(), Hand.MAIN_HAND);
		if (r.isAccepted()) {
			p.swingHand(Hand.MAIN_HAND);
		}
		return r.isAccepted();
	}

	/**
	 * Right-click a mob with an item from the inventory (a bone for a wolf), like a player:
	 * holds it, looks at the mob and uses it on it. False if it's out of reach or we have none.
	 */
	public boolean useItemOnEntity(Entity target, Predicate<ItemStack> which) {
		CompanionPlayer p = player();
		if (p.squaredDistanceTo(target) > ATTACK_REACH * ATTACK_REACH) {
			return false;
		}
		int slot = Inventories.find(p.getInventory(), which);
		if (slot < 0) {
			return false;
		}
		Inventories.select(p, slot);
		lookAt(target);
		ActionResult r = p.interact(target, Hand.MAIN_HAND);
		if (r.isAccepted()) {
			p.swingHand(Hand.MAIN_HAND);
		}
		return r.isAccepted();
	}

	public boolean isUsingItem() {
		CompanionPlayer p = player();
		return p != null && p.isUsingItem();
	}

	/** Drop up to count items matching the predicate in the look direction. Returns how many were dropped. */
	public int dropItems(Predicate<ItemStack> which, int count) {
		CompanionPlayer p = player();
		PlayerInventory inv = p.getInventory();
		int dropped = 0;
		for (int i = 0; i < inv.size() && dropped < count; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !which.test(s)) {
				continue;
			}
			ItemStack taken = inv.removeStack(i, Math.min(count - dropped, s.getCount()));
			dropped += taken.getCount();
			ItemEntity e = p.dropItem(taken, false, true);
			if (e != null) {
				e.setPickupDelay(10);
				if (reserveFor != null) {
					e.setOwner(reserveFor);
				}
			}
		}
		if (dropped > 0) {
			p.swingHand(Hand.MAIN_HAND);
		}
		return dropped;
	}

	/** Face the receiver and toss them items. */
	public int giveItems(Entity receiver, Predicate<ItemStack> which, int count) {
		lookAt(receiver);
		applyLook(player()); // snap closer before throwing so the items fly the right way
		applyLook(player());
		reserveFor = receiver.getUuid();
		try {
			return dropItems(which, count);
		} finally {
			reserveFor = null;
		}
	}

	/** While handing items over: only this player may pick them up (nobody else in a crowd grabs them). */
	@Nullable
	private java.util.UUID reserveFor;
}
