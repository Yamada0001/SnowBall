package org.snowballmc.snowball.experimental.world;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.snowballmc.snowball.experimental.ExperimentalConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Suite
@SelectClasses(WorldTpsBarTest.class)
public class WorldTpsBarTestSuite {
}

class WorldTpsBarTest {
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long TARGET = 50 * MS;

    @Test
    void onlyAcceptedOptInEnablesTheCommandAndOnlyWorldModeSamplesWorlds() {
        assertFalse(WorldTpsBar.enabled(new ExperimentalConfig.World(false, false, 4, false, true, true)));
        assertFalse(WorldTpsBar.enabled(new ExperimentalConfig.World(true, false, 4, false, true, false)));
        assertFalse(WorldTpsBar.enabled(new ExperimentalConfig.World(true, true, 4, false, false, false)));
        final var server = new ExperimentalConfig.World(true, true, 1, false, true, true);
        final var world = new ExperimentalConfig.World(true, true, 1, false, true, false);
        assertTrue(WorldTpsBar.enabled(server));
        assertTrue(WorldTpsBar.enabled(world));
        assertFalse(WorldTpsBar.samplesWorlds(server));
        assertTrue(WorldTpsBar.samplesWorlds(world));
    }

    @Test
    void reportsPacingRatherThanWorldTickDurationAndKeepsWorldsSeparate() {
        final WorldTickPacing fast = new WorldTickPacing();
        final WorldTickPacing slow = new WorldTickPacing();
        assertNull(fast.average(0L, TARGET));
        fast.completedTick(0L, 3 * MS);
        slow.completedTick(0L, 3 * MS);
        assertNull(fast.average(3 * MS, TARGET));
        fast.completedTick(TARGET, TARGET + 3 * MS);
        slow.completedTick(2 * TARGET, 2 * TARGET + 3 * MS);
        assertEquals(20.0, fast.average(2 * TARGET + 3 * MS, TARGET));
        assertEquals(10.0, slow.average(2 * TARGET + 3 * MS, TARGET));
    }

    @Test
    void intentionalEmptyPauseStartsANewSampleWindowEvenWhenShort() {
        final WorldTickPacing pacing = new WorldTickPacing();
        pacing.completedTick(0L, MS);
        pacing.completedTick(2 * TARGET, 2 * TARGET + MS);
        assertEquals(10.0, pacing.average(2 * TARGET + MS, TARGET));

        pacing.resetForEmptyPause();
        pacing.resetForEmptyPause(); // The empty branch can run on multiple consecutive ticks.
        final long resumed = 6 * TARGET;
        assertNull(pacing.average(resumed, TARGET));
        pacing.completedTick(resumed, resumed + MS);
        assertNull(pacing.average(resumed + MS, TARGET), "one resumed tick does not establish pacing");
        pacing.completedTick(resumed + TARGET, resumed + TARGET + MS);
        assertEquals(20.0, pacing.average(resumed + TARGET + MS, TARGET));
    }

    @Test
    void staleSamplesAreNotReportedButARealLagGapStillCounts() {
        final WorldTickPacing pacing = new WorldTickPacing();
        final long gap = WorldTickPacing.WINDOW_NANOS + 100 * MS;
        pacing.completedTick(0L, MS);
        pacing.completedTick(TARGET, TARGET + MS);
        assertNull(pacing.average(gap, TARGET));
        pacing.completedTick(gap, gap + MS);
        assertEquals(1.0E9 / (gap - TARGET), pacing.average(gap + MS, TARGET));
    }
}
