package org.snowballmc.snowball.experimental.culling;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Suite
@SelectClasses(RaytraceCullingTest.class)
public class RaytraceCullingTestSuite {
}

class RaytraceCullingTest {
    @Test
    void oneOpaquePlaneIsEnoughButARayThroughEveryPlaneIsVisible() {
        assertTrue(new Raytrace.Capture(new byte[] {1, 0, 1, 1, 1}, new int[] {2, 5}).fullyOccluded());
        assertFalse(new Raytrace.Capture(new byte[] {1, 0, 0, 1, 1}, new int[] {2, 5}).fullyOccluded());
        assertFalse(new Raytrace.Capture(new byte[] {}, new int[] {}).fullyOccluded());
    }

    @Test
    void removingOneWallCellCannotKeepAnOldProofFresh() {
        final long captured = 123_000_000L;
        assertTrue(Raytrace.fresh(captured, captured + 149_999_999L, 50));
        assertFalse(Raytrace.fresh(captured, captured + 150_000_000L, 50));
        assertFalse(Raytrace.fresh(captured, captured + 500_000_000L, Integer.MAX_VALUE));
        assertTrue(new Raytrace.Capture(new byte[] {1, 1, 1, 1}, new int[] {4}).fullyOccluded());
        assertFalse(new Raytrace.Capture(new byte[] {1, 0, 1, 1}, new int[] {4}).fullyOccluded());
    }

    @Test
    void projectionIncludesNegativeCoordinatesAndBothSidesOfAChunkEdge() {
        final var eye = new Raytrace.Box(-8, 1.4, -16.1, -7.5, 1.9, -15.9);
        final var body = new Raytrace.Box(8, 0.25, -16.2, 8.5, 2.2, -15.8);
        assertEquals(0, Raytrace.axis(eye, body));
        final var slice = Raytrace.slice(eye, body, 0, 0);
        assertEquals(-17, slice.minV());
        assertEquals(-16, slice.maxV());
        assertEquals(slice, Raytrace.slice(body, eye, 0, 0));
        assertNull(Raytrace.slice(eye, body, 0, -8));
    }

    @Test
    void motionOutsideTheEyeOrTargetEnvelopeCannotReuseTheProof() {
        final var eye = new Raytrace.Box(0, 1.62, 0, 0, 1.62, 0).expand(0.25);
        assertTrue(eye.contains(new Raytrace.Box(0.125, 1.62, 0, 0.125, 1.62, 0)));
        assertFalse(eye.contains(new Raytrace.Box(0.26, 1.62, 0, 0.26, 1.62, 0)));
        final var target = new Raytrace.Box(8, 0, 0, 9, 2, 1);
        assertFalse(target.expand(0.25).contains(new Raytrace.Box(8, 0, 0, 9.3, 2, 1)));
        assertEquals(64, target.distanceSquared(0, 1.62, 0));
        assertFalse(target.fits(1));
    }

    @Test
    void projectedRectangleContainsEverySampledRayInAllDirections() {
        final Random random = new Random(257);
        for (int axis = 0; axis < 3; ++axis) {
            final var eye = new Raytrace.Box(-5, -5, -5, -3, -3, -3);
            final var target = new Raytrace.Box(1, 2, 3, 4, 5, 6);
            final var slice = Raytrace.slice(eye, target, axis, -1);
            assertEquals(slice, Raytrace.slice(target, eye, axis, -1));
            for (int i = 0; i < 1000; ++i) {
                final double from = sample(random, eye, axis);
                final double to = sample(random, target, axis);
                final double t = (-0.5 - from) / (to - from);
                final int u = projectedCell(random, eye, target, (axis + 1) % 3, t);
                final int v = projectedCell(random, eye, target, (axis + 2) % 3, t);
                assertTrue(slice.minU() <= u && u <= slice.maxU());
                assertTrue(slice.minV() <= v && v <= slice.maxV());
            }
        }
    }

    @Test
    void workersReadOnlyImmutableCopiesAndCloseRejectsNewWork() throws Exception {
        final byte[] cells = {1};
        final int[] ends = {1};
        final var opaque = new Raytrace.Capture(cells, ends);
        final var open = new Raytrace.Capture(new byte[] {0}, ends);
        cells[0] = 0;
        ends[0] = 0;
        final CullingWorker worker = new CullingWorker();
        try (worker) {
            final var result = worker.submit(1, List.of(opaque, open)).get(5, TimeUnit.SECONDS);
            assertTrue(result.get(0));
            assertFalse(result.get(1));
        }
        assertNull(worker.submit(1, List.of(opaque)));
    }

    private static int projectedCell(final Random random, final Raytrace.Box eye, final Raytrace.Box target, final int axis, final double t) {
        final double a = sample(random, eye, axis);
        return (int)Math.floor(a + (sample(random, target, axis) - a) * t);
    }

    private static double sample(final Random random, final Raytrace.Box box, final int axis) {
        return box.min(axis) + (box.max(axis) - box.min(axis)) * random.nextDouble();
    }
}
