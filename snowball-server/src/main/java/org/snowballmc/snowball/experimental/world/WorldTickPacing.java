package org.snowballmc.snowball.experimental.world;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** Samples successful world tick invocations, not the world's simulation clock or CPU time. */
public final class WorldTickPacing {
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final TickData samples = new TickData(WINDOW_NANOS);
    private boolean hasPreviousTick;
    private long previousStart;
    private long lastCompletion;

    // Written only by the world tick owner; readers run on the coordinator after the world tick barrier.
    public void completedTick(final long start, final long end) {
        if (this.hasPreviousTick) {
            this.samples.addDataFrom(new TickTime(this.previousStart, start, start, 0L, end, 0L, 0L, 0L, false));
        }
        this.hasPreviousTick = true;
        this.previousStart = start;
        this.lastCompletion = end;
    }

    public @Nullable Double average(final long now, final long targetInterval) {
        if (!this.hasPreviousTick || now - this.lastCompletion > WINDOW_NANOS) {
            return null;
        }
        return this.samples.getTPSAverage(null, targetInterval);
    }
}
