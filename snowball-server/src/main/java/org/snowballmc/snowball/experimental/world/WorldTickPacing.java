package org.snowballmc.snowball.experimental.world;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** Samples successful world tick invocations, not the world's simulation clock or CPU time. */
public final class WorldTickPacing {
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    private @Nullable TickData samples;
    private boolean hasPreviousTick;
    private long previousStart;
    private long lastCompletion;

    // Written only by the world tick owner; readers run on the coordinator after the world tick barrier.
    public void completedTick(final long start, final long end) {
        if (this.hasPreviousTick) {
            if (this.samples == null) {
                this.samples = new TickData(WINDOW_NANOS);
            }
            this.samples.addDataFrom(new TickTime(this.previousStart, start, start, 0L, end, 0L, 0L, 0L, false));
        }
        this.hasPreviousTick = true;
        this.previousStart = start;
        this.lastCompletion = end;
    }

    // Called by the coordinator only when it deliberately skips world ticks, never on a timing gap alone.
    public void resetForEmptyPause() {
        this.samples = null;
        this.hasPreviousTick = false;
    }

    public @Nullable Double average(final long now, final long targetInterval) {
        final TickData samples = this.samples;
        if (samples == null || now - this.lastCompletion > WINDOW_NANOS) {
            return null;
        }
        return samples.getTPSAverage(null, targetInterval);
    }
}
