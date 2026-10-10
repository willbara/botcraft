package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Idle head movement: glance at the owner (or a nearby player) now and then, look around
 * otherwise, and look back at players who are looking at us.
 */
public class Glances {
	private final Random random = new Random();
	private int timer;
	@Nullable
	private Vec3d point;
	@Nullable
	private PlayerEntity player;
	@Nullable
	private net.minecraft.entity.Entity animal;
	private int watchedTicks;
	private int crouchTicks;
	private long lastCrouchWave;

	public void tick(Companion c, @Nullable PlayerEntity preferred) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return;
		}
		if (crouchTicks > 0) {
			// The player "hello": a quick double crouch.
			crouchTicks--;
			c.actions().sneak(crouchTicks % 6 >= 3);
		} else if (crouchTicks == 0) {
			c.actions().sneak(false);
			crouchTicks = -1;
		}
		PlayerEntity watcher = preferred != null && isLookingAt(preferred, p) ? preferred : null;
		if (watcher != null) {
			c.actions().lookAt(watcher); // someone is looking at us: look back
			if (++watchedTicks == 25 && p.age - lastCrouchWave > 20 * 60 && random.nextFloat() < 0.5F + c.data.chattiness * 0.5F) {
				crouchTicks = 12;
				lastCrouchWave = p.age;
			}
			return;
		}
		watchedTicks = 0;
		if (--timer <= 0) {
			timer = 30 + random.nextInt(70);
			PlayerEntity near = preferred != null ? preferred : p.getWorld().getClosestPlayer(p, 8.0);
			var animals = p.getWorld().getEntitiesByClass(net.minecraft.entity.passive.AnimalEntity.class, p.getBoundingBox().expand(8), a -> true);
			animal = null;
			if (near != null && near != p && random.nextFloat() < 0.5F) {
				player = near;
				point = null;
			} else if (!animals.isEmpty() && random.nextFloat() < 0.4F) {
				player = null;
				point = null;
				animal = animals.get(random.nextInt(animals.size())); // "aw, a sheep"
			} else {
				player = null;
				float yaw = p.getYaw() + (random.nextFloat() - 0.5F) * 140.0F;
				float pitch = (random.nextFloat() - 0.6F) * 40.0F;
				point = p.getEyePos().add(Vec3d.fromPolar(pitch, yaw).multiply(5.0));
			}
		}
		if (player != null && player.isAlive() && player.squaredDistanceTo(p) < 24 * 24) {
			c.actions().lookAt(player);
		} else if (animal != null && animal.isAlive()) {
			c.actions().lookAt(animal);
		} else if (point != null) {
			c.actions().lookAt(point);
		}
		// Bored and chatty: the occasional hop on the spot.
		if (random.nextFloat() < 0.002F * c.data.chattiness && p.isOnGround()) {
			c.actions().jump();
		}
	}

	static boolean isLookingAt(PlayerEntity viewer, CompanionPlayer target) {
		if (viewer.squaredDistanceTo(target) > 10 * 10) {
			return false;
		}
		Vec3d look = viewer.getRotationVec(1.0F);
		Vec3d to = target.getEyePos().subtract(viewer.getEyePos()).normalize();
		return look.dotProduct(to) > MathHelper.cos(8.0F * MathHelper.RADIANS_PER_DEGREE);
	}
}
