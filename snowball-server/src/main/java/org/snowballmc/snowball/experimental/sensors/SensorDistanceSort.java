package org.snowballmc.snowball.experimental.sensors;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.snowballmc.snowball.experimental.ExperimentalConfig;

/** Owner-side bridge for the two audited vanilla sensor distance sorts. */
public final class SensorDistanceSort {
    private SensorDistanceSort() {
    }

    public static boolean trySort(final ServerLevel level, final LivingEntity body, final List<? extends Entity> candidates) {
        if (!ParallelSensorPhase.supportsSize(candidates.size())) {
            return false;
        }
        final var settings = ExperimentalConfig.current().sensors();
        if (!settings.enabled()) {
            return false;
        }
        final var server = level.getServer();
        // Only opt in on the vanilla owner. A future parallel world owner needs a separate audit.
        if (Thread.currentThread() != server.getRunningThread() || body.level() != level || body.isRemoved()
            || level.tickRateManager().isEntityFrozen(body)) {
            return false;
        }
        return server.snowballSensorPhase.trySort(candidates, body::distanceToSqr, settings.maxThreads());
    }
}
