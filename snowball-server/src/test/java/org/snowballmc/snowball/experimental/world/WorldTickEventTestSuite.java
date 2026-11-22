package org.snowballmc.snowball.experimental.world;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Suite
@SelectClasses(WorldTickEventTest.class)
public class WorldTickEventTestSuite {
}

class WorldTickEventTest {
    @TempDir
    Path directory;

    @BeforeEach
    void requireFlightRecorder() {
        assumeTrue(FlightRecorder.isAvailable());
    }

    @Test
    void isDisabledUnlessTheRecordingOptsIn() throws Exception {
        final Path path = this.directory.resolve("disabled.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            assertFalse(WorldTickEvent.isRecordingEnabled());
            recordTick("minecraft:overworld", 7, true);
            recording.stop();
            recording.dump(path);
        }
        assertTrue(readWorldTicks(path).isEmpty());
    }

    @Test
    void recordsEachDimensionAndOutcomeOnTheCallingThread() throws Exception {
        final Path path = this.directory.resolve("enabled.jfr");
        final long callingThreadId = Thread.currentThread().threadId();
        try (Recording recording = new Recording()) {
            recording.enable(WorldTickEvent.NAME).withoutThreshold();
            recording.start();
            assertTrue(WorldTickEvent.isRecordingEnabled());
            recordTick("minecraft:overworld", 22, true);
            recordTick("example:skylands", 22, false);
            recordTick("minecraft:overworld", 23, true);
            recording.stop();
            recording.dump(path);
        }

        final List<RecordedEvent> ticks = readWorldTicks(path);
        assertEquals(3, ticks.size());
        assertTick(ticks.get(0), "minecraft:overworld", 22, true, callingThreadId);
        assertTick(ticks.get(1), "example:skylands", 22, false, callingThreadId);
        assertTick(ticks.get(2), "minecraft:overworld", 23, true, callingThreadId);
    }

    @Test
    void honorsTheRecordingDurationThreshold() throws Exception {
        final Path path = this.directory.resolve("threshold.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(WorldTickEvent.NAME).withThreshold(Duration.ofHours(1));
            recording.start();
            recordTick("minecraft:overworld", 1, true);
            recording.stop();
            recording.dump(path);
        }
        assertTrue(readWorldTicks(path).isEmpty());
    }

    private static void recordTick(final String dimension, final int tick, final boolean completed) {
        final WorldTickEvent event = new WorldTickEvent(dimension, tick);
        event.begin();
        event.finish(completed);
    }

    private static List<RecordedEvent> readWorldTicks(final Path path) throws Exception {
        return RecordingFile.readAllEvents(path).stream()
            .filter(event -> WorldTickEvent.NAME.equals(event.getEventType().getName()))
            .toList();
    }

    private static void assertTick(final RecordedEvent event, final String dimension, final int tick, final boolean completed, final long callingThreadId) {
        assertEquals(dimension, event.getString("dimension"));
        assertEquals(tick, event.getInt("serverTick"));
        assertEquals(completed, event.getBoolean("completed"));
        assertEquals(callingThreadId, event.getThread().getJavaThreadId());
        assertFalse(event.getDuration().isNegative());
    }
}
