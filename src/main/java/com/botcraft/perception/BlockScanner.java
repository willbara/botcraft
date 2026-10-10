package com.botcraft.perception;

import com.botcraft.Companion;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.TickBudget;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Time-sliced search for blocks of a type around the companion. Whole 16^3 sections are
 * skipped cheaply with the palette check ({@code PalettedContainer#hasAny}), so scanning a
 * forest-free area costs almost nothing. Only already-loaded chunks are read.
 *
 * <p>Usage: {@link #start}, then call {@link #tick()} each tick until it returns true, then
 * read {@link #results()} (nearest first). Results stay cached until the next start.
 */
public class BlockScanner {
	private final Companion companion;

	private Predicate<BlockState> predicate = s -> false;
	private BlockPos center = BlockPos.ORIGIN;
	private int radius;
	private int minY;
	private int maxY;
	private final List<ChunkSectionPos> sections = new ArrayList<>();
	private int sectionIndex;
	private final List<BlockPos> found = new ArrayList<>();
	private boolean running;
	private boolean done;
	private long finishedAtTick = -1;

	public BlockScanner(Companion companion) {
		this.companion = companion;
	}

	public void start(Predicate<BlockState> predicate, int radius, int below, int above) {
		CompanionPlayer p = companion.player();
		if (p == null) {
			return;
		}
		this.predicate = predicate;
		this.center = p.getBlockPos();
		this.radius = radius;
		this.minY = Math.max(p.getWorld().getBottomY(), center.getY() - below);
		this.maxY = Math.min(p.getWorld().getTopY() - 1, center.getY() + above);
		sections.clear();
		found.clear();
		ChunkSectionPos c = ChunkSectionPos.from(center);
		int cr = (radius >> 4) + 1;
		for (int sx = c.getSectionX() - cr; sx <= c.getSectionX() + cr; sx++) {
			for (int sz = c.getSectionZ() - cr; sz <= c.getSectionZ() + cr; sz++) {
				for (int sy = ChunkSectionPos.getSectionCoord(minY); sy <= ChunkSectionPos.getSectionCoord(maxY); sy++) {
					sections.add(ChunkSectionPos.from(sx, sy, sz));
				}
			}
		}
		// Nearest sections first, so a budget-limited scan still finds close things early.
		sections.sort(Comparator.comparingDouble(s -> s.getCenterPos().getSquaredDistance(center)));
		sectionIndex = 0;
		running = true;
		done = false;
	}

	public boolean isRunning() {
		return running;
	}

	public boolean isDone() {
		return done;
	}

	public long finishedAtTick() {
		return finishedAtTick;
	}

	/** @return true when the scan has finished. */
	public boolean tick(long serverTick) {
		if (!running) {
			return done;
		}
		CompanionPlayer p = companion.player();
		if (p == null) {
			running = false;
			return false;
		}
		ServerWorld world = p.getServerWorld();
		int processed = 0;
		while (sectionIndex < sections.size()) {
			if (processed > 0 && !TickBudget.hasTime()) {
				return false;
			}
			scanSection(world, sections.get(sectionIndex++));
			processed++;
		}
		found.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(center)));
		running = false;
		done = true;
		finishedAtTick = serverTick;
		return true;
	}

	private void scanSection(ServerWorld world, ChunkSectionPos sp) {
		WorldChunk chunk = world.getChunkManager().getWorldChunk(sp.getSectionX(), sp.getSectionZ());
		if (chunk == null) {
			return;
		}
		int index = chunk.getSectionIndex(sp.getMinY());
		if (index < 0 || index >= chunk.getSectionArray().length) {
			return;
		}
		ChunkSection section = chunk.getSectionArray()[index];
		if (section == null || section.isEmpty() || !section.getBlockStateContainer().hasAny(predicate)) {
			return;
		}
		int r2 = radius * radius;
		for (int y = 0; y < 16; y++) {
			int wy = sp.getMinY() + y;
			if (wy < minY || wy > maxY) {
				continue;
			}
			for (int z = 0; z < 16; z++) {
				int wz = sp.getMinZ() + z;
				for (int x = 0; x < 16; x++) {
					int wx = sp.getMinX() + x;
					int dx = wx - center.getX();
					int dz = wz - center.getZ();
					if (dx * dx + dz * dz > r2) {
						continue;
					}
					if (predicate.test(section.getBlockState(x, y, z))) {
						found.add(new BlockPos(wx, wy, wz));
					}
				}
			}
		}
	}

	/** Matches from the last finished scan, nearest to the scan centre first. */
	public List<BlockPos> results() {
		return found;
	}
}
