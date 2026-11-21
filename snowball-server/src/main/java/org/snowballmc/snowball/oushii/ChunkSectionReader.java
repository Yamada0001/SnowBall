/*
 * Copyright (C) 2026 Paulem
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.snowballmc.snowball.oushii;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Chunk/section access for the blast solver, adapted from Oushii's {@code SCUtils} +
 * {@code FastExplosionEngine} inner loop for Minecraft 26.3 / Moonrise.
 *
 * <p>A single instance is reused for one blast. It caches the last chunk and the last section so
 * the solver's inner loop performs at most one {@code getChunkNow} per chunk column and at most
 * one palette read per section, which is where most of the win on an open-air blast comes from:
 * an empty section answers {@code null} without touching the palette at all.
 *
 * <p>Two 26.3-specific notes the mod did not have to deal with:
 * <ul>
 *   <li>{@code LevelChunk#setBlockState} is inherited from {@code ChunkAccess}; the 3-arg
 *       {@code (BlockPos, BlockState, int)} overload lives on {@link LevelChunk} and still runs
 *       heightmap, light-source and block-entity teardown ({@code removeBlockEntity},
 *       {@code onRemove}) even with {@code flags == 0}.</li>
 *   <li>{@code ChunkAccess#getSectionIndex(int)} and {@code #getSections()} are declared on
 *       {@code ChunkAccess}, not on {@code LevelChunk}, so both are reached through the chunk
 *       reference without a cast.</li>
 * </ul>
 *
 * <p>Server thread only. Not thread safe.
 */
public final class ChunkSectionReader {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    private final ServerChunkCache chunkSource;
    private final LevelLightEngine lightEngine;

    private LevelChunk chunk;
    private LevelChunkSection section;
    private boolean sectionIsAir = true;
    private boolean chunkDirty;

    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;
    private int cachedSectionIndex = Integer.MIN_VALUE;

    public ChunkSectionReader(final ServerLevel level) {
        this.level = level;
        this.chunkSource = level.getChunkSource();
        this.lightEngine = level.getLightEngine();
    }

    public ServerLevel level() {
        return this.level;
    }

    /**
     * Drops the cached chunk so the next {@link #stateAt} call re-fetches it. Called between the
     * solver's octants is not necessary; called by {@link #flush()} is.
     */
    public void reset() {
        this.chunk = null;
        this.section = null;
        this.cachedChunkX = Integer.MIN_VALUE;
        this.cachedChunkZ = Integer.MIN_VALUE;
        this.cachedSectionIndex = Integer.MIN_VALUE;
        this.sectionIsAir = true;
    }

    /**
     * Block state at the given world coordinates, or {@code null} when the chunk is not loaded.
     *
     * <p>{@code null} means "no terrain here": the solver treats it as an impassable void so a
     * blast never drags a chunk in from disk mid-tick. This mirrors the mod, which refuses to
     * load chunks during a blast.
     */
    public BlockState stateAt(final int x, final int y, final int z) {
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;

        if (chunkX != this.cachedChunkX || chunkZ != this.cachedChunkZ) {
            this.settleDirty();
            this.chunk = this.chunkSource.getChunkNow(chunkX, chunkZ);
            this.cachedChunkX = chunkX;
            this.cachedChunkZ = chunkZ;
            this.cachedSectionIndex = Integer.MIN_VALUE;
        }

        // Never drag a chunk in from disk mid-blast; unloaded terrain just stops it.
        if (this.chunk == null) return null;

        final int sectionIndex = this.chunk.getSectionIndex(y);
        if (sectionIndex != this.cachedSectionIndex) {
            this.cachedSectionIndex = sectionIndex;
            final LevelChunkSection[] sections = this.chunk.getSections();
            this.section = (sectionIndex >= 0 && sectionIndex < sections.length)
                    ? sections[sectionIndex] : null;
            this.sectionIsAir = this.section == null || this.section.hasOnlyAir();
        }

        // Empty sections skip the palette read entirely, which is most of an open air blast.
        if (this.sectionIsAir) return null;
        return this.section.getBlockState(x & 15, y & 15, z & 15);
    }

    /**
     * Writes air without neighbour updates or a per-block packet: the caller batches those in
     * {@code BlastField}'s edge pass. Heightmaps, sky-light sources, section emptiness and block
     * entity removal are still handled by {@code LevelChunk#setBlockState} even at flags 0.
     */
    public void removeBlock(final BlockPos pos) {
        if (this.chunk == null) return;
        this.chunk.setBlockState(pos, AIR, 0);
        this.chunkSource.blockChanged(pos);
        this.lightEngine.checkBlock(pos);
        this.chunkDirty = true;
    }

    /** Marks the chunk unsaved and clears the caches. Call once when a blast is over. */
    public void flush() {
        this.settleDirty();
        this.reset();
    }

    private void settleDirty() {
        if (this.chunkDirty && this.chunk != null) {
            this.chunk.markUnsaved();
        }
        this.chunkDirty = false;
    }
}
