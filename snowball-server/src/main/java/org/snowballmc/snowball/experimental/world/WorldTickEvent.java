package org.snowballmc.snowball.experimental.world;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Wall-clock duration of one serial world tick, not its tick rate. */
@Name(WorldTickEvent.NAME)
@Label("World Tick")
@Category({"SnowBall", "Ticking"})
@Enabled(false)
@StackTrace(false)
public final class WorldTickEvent extends Event {
    public static final String NAME = "snowball.WorldTick";
    private static final EventType TYPE = EventType.getEventType(WorldTickEvent.class);

    @Label("Dimension")
    private final String dimension;
    @Label("Server Tick")
    private final int serverTick;
    @Label("Completed Normally")
    private boolean completed;

    public WorldTickEvent(final String dimension, final int serverTick) {
        this.dimension = dimension;
        this.serverTick = serverTick;
    }

    public static boolean isRecordingEnabled() {
        return TYPE.isEnabled();
    }

    public void finish(final boolean completed) {
        this.end();
        this.completed = completed;
        this.commit();
    }
}
