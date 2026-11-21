/*
 * Copyright (C) 2026 Paulem
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.snowballmc.snowball.oushii;

import org.snowballmc.snowball.LeavesConfig;

/**
 * Bridges Oushii's tunables onto Leaves' config system.
 *
 * <p>Oushii ships four knobs. Two of them ({@code maxExplosionParticlesPerTick},
 * {@code maxRenderedTnt}) are client-only render throttles and are dropped by the port, because
 * the client mixin is skipped and a server never renders TNT. The two server-side knobs are
 * kept and read straight from {@link LeavesConfig}:
 *
 * <ul>
 *   <li>{@code performance.explosion-engine} gates the whole feature (default {@code false}).</li>
 *   <li>{@code performance.explosion-engine-cluster-radius} mirrors Oushii's
 *       {@code clusterRadius} (default {@code 3.0}).</li>
 *   <li>{@code performance.explosion-engine-max-primed} mirrors Oushii's
 *       {@code maxPrimedPerExplosion} (default {@code 32}).</li>
 * </ul>
 *
 * <p>Every accessor is a method rather than a field so a {@code /leaves reload} between two
 * blasts takes effect immediately, matching how the rest of {@code LeavesConfig} behaves.
 */
public final class OushiiSettings {

    private OushiiSettings() {
    }

    /** Master switch. Read once per blast, never cached, so reload works. */
    public static boolean enabled() {
        return LeavesConfig.performance.explosionEngine;
    }

    /** Radius (in blocks) inside which same-tick blasts merge. {@code <= 0} disables merging. */
    public static double clusterRadius() {
        final double radius = LeavesConfig.performance.explosionEngineClusterRadius;
        if (radius < 0.0) return 0.0;
        return Math.min(radius, 20.0);
    }

    /** Cap on TNT entities primed by one blast, so a chain cannot turn into an entity cascade. */
    public static int maxPrimedPerExplosion() {
        final int max = LeavesConfig.performance.explosionEngineMaxPrimed;
        return Math.max(1, Math.min(max, 256));
    }
}
