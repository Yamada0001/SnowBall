/*
 * Copyright (C) 2026 Paulem
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.snowballmc.snowball.oushii;

import it.unimi.dsi.fastutil.objects.Reference2FloatMap;
import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-blast memo of what a block costs the field, keyed on {@link BlockState}.
 *
 * <p>Vanilla asks the damage calculator once per distinct block position. That hook is far too
 * heavy for a grid solver's inner loop — it boxes a {@code float} into an {@code Optional} and,
 * on a modded stack, may run an arbitrary amount of code — so Oushii asks it once per distinct
 * {@code BlockState} and reuses the answer. Mods that retune a block's resistance key off the
 * block, not the position, so this is behaviour-preserving for every practical case; a mod that
 * varies resistance <em>per position</em> is read once and applied everywhere, which is the one
 * thing this trades away.
 *
 * <h2>Why 26.3 reads differently than the mod</h2>
 * Oushii did {@code calculator.getBlockExplosionResistance(...).orElse(0.0f)} and then added
 * {@code resistance + 0.3}. Vanilla 26.3 actually does
 * {@code (resistance.orElse(-0.3f) + 0.3f) * 0.3f} per 0.3-block ray step. Unwrapping an empty
 * {@code Optional} to {@code 0.0} is <em>not</em> equivalent: {@code Optional#empty()} means
 * "this block does not participate", i.e. zero added cost, whereas {@code 0.0} means "cost of an
 * ordinary unresisting block". The difference shows up for the nether-portal calculator
 * ({@code PrimedTnt.USED_PORTAL_DAMAGE_CALCULATOR}), which returns {@code Optional#empty()} for
 * portal blocks, and for any mod that vetoes via an empty Optional. This class therefore caches
 * the <em>vanilla-equivalent per-block energy price</em>
 * {@code (resistance.orElse(-0.3f) + 0.3f)}, so the solver can simply do {@code cost += price}.
 *
 * <p>Server thread only, one instance per blast.
 */
public final class BlockResistanceCache {

    /**
     * Vanilla's own sentinel for "the calculator declined to price this block", i.e. zero
     * resistance. {@code (ZERO_RESISTANCE + 0.3F) * 0.3F == 0}, see
     * {@code ServerExplosion#getOrCacheExplosionBlock}.
     */
    private static final float ZERO_RESISTANCE = -0.3f;

    /** Vanilla's constant material term; added to the raw resistance to form the per-block price. */
    static final float MATERIAL_COST = 0.3f;

    private final ExplosionDamageCalculator calculator;
    private final Explosion explosion;
    private final Level level;
    private final Reference2FloatMap<BlockState> cache = new Reference2FloatOpenHashMap<>();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    public BlockResistanceCache(
            final ExplosionDamageCalculator calculator,
            final Explosion explosion,
            final Level level
    ) {
        this.calculator = calculator;
        this.explosion = explosion;
        this.level = level;
        // NaN is a legal cache value in general, so it cannot double as the "absent" marker.
        // defaultReturnValue(-1.0f) would clash with a mod returning a negative resistance, so a
        // presence check is used instead; see price().
        this.cache.defaultReturnValue(Float.NaN);
    }

    /**
     * Energy one block of {@code state} takes out of the field, expressed per block of travel.
     *
     * <p>Vanilla burns {@code (resistance + 0.3) * 0.3} every 0.3 blocks, i.e. exactly
     * {@code resistance + 0.3} per block, so that sum is what is returned.
     */
    public float price(final BlockState state, final int x, final int y, final int z) {
        final float cached = this.cache.getFloat(state);
        if (!Float.isNaN(cached)) return cached;

        this.probe.set(x, y, z);
        // The calculator already folds in the fluid's own resistance.
        final float raw = this.calculator
                .getBlockExplosionResistance(this.explosion, this.level, this.probe, state, state.getFluidState())
                .orElse(ZERO_RESISTANCE);
        final float price = raw + MATERIAL_COST;
        this.cache.put(state, price);
        return price;
    }
}
