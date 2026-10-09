package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.entity.CompanionPlayer;
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

	public void tick(Companion c, @Nullable PlayerEntity preferred) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return;
		}
		PlayerEntity watcher = preferred != null && isLookingAt(preferred, p) ? preferred : null;
		if (watcher != null) {
			c.actions().lookAt(watcher); // someone is looking at us: look back
			return;
		}
		if (--timer <= 0) {
			timer = 30 + random.nextInt(70);
			PlayerEntity near = preferred != null ? preferred : p.getWorld().getClosestPlayer(p, 8.0);
			if (near != null && near != p && random.nextFloat() < 0.55F) {
				player = near;
				point = null;
			} else {
				player = null;
				float yaw = p.getYaw() + (random.nextFloat() - 0.5F) * 140.0F;
				float pitch = (random.nextFloat() - 0.6F) * 40.0F;
				point = p.getEyePos().add(Vec3d.fromPolar(pitch, yaw).multiply(5.0));
			}
		}
		if (player != null && player.isAlive() && player.squaredDistanceTo(p) < 24 * 24) {
			c.actions().lookAt(player);
		} else if (point != null) {
			c.actions().lookAt(point);
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
