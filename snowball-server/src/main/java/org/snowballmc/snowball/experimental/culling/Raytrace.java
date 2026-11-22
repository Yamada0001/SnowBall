package org.snowballmc.snowball.experimental.culling;

/** Detached geometry; no Minecraft types or mutable world state. */
final class Raytrace {
    private static final double EDGE_MARGIN = 1.0e-6;

    private Raytrace() {
    }

    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean valid() {
            return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                && minX <= maxX && minY <= maxY && minZ <= maxZ;
        }

        boolean contains(final Box other) {
            return minX <= other.minX && minY <= other.minY && minZ <= other.minZ
                && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
        }

        boolean fits(final double limit) {
            return maxX - minX <= limit && maxY - minY <= limit && maxZ - minZ <= limit;
        }

        Box expand(final double margin) {
            return new Box(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
        }

        double min(final int axis) {
            return axis == 0 ? minX : axis == 1 ? minY : minZ;
        }

        double max(final int axis) {
            return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
        }

        double distanceSquared(final double x, final double y, final double z) {
            final double dx = Math.max(0.0, Math.max(minX - x, x - maxX));
            final double dy = Math.max(0.0, Math.max(minY - y, y - maxY));
            final double dz = Math.max(0.0, Math.max(minZ - z, z - maxZ));
            return dx * dx + dy * dy + dz * dz;
        }

        double farthestSquared(final double x, final double y, final double z) {
            final double dx = Math.max(Math.abs(minX - x), Math.abs(maxX - x));
            final double dy = Math.max(Math.abs(minY - y), Math.abs(maxY - y));
            final double dz = Math.max(Math.abs(minZ - z), Math.abs(maxZ - z));
            return dx * dx + dy * dy + dz * dz;
        }
    }

    record Slice(int plane, int minU, int maxU, int minV, int maxV) {
        int cells() {
            return (maxU - minU + 1) * (maxV - minV + 1);
        }
    }

    static boolean fresh(final long captured, final long now, final int intervalMs) {
        return now - captured < Math.clamp(intervalMs * 3L, 150L, 500L) * 1_000_000L;
    }

    static int axis(final Box eye, final Box target) {
        int bestAxis = -1;
        double bestGap = 0.0;
        for (int axis = 0; axis < 3; ++axis) {
            final double gap = Math.max(target.min(axis) - eye.max(axis), eye.min(axis) - target.max(axis));
            if (gap > bestGap) {
                bestGap = gap;
                bestAxis = axis;
            }
        }
        return bestAxis;
    }

    /** The middle of a voxel layer is covered only if every possible sightline crosses an opaque cell. */
    static Slice slice(final Box eye, final Box target, final int axis, final int plane) {
        final double middle = plane + 0.5;
        if (!((eye.max(axis) < middle && middle < target.min(axis))
            || (target.max(axis) < middle && middle < eye.min(axis)))) {
            return null;
        }
        // For an axis-aligned separating plane, the min/max interpolation factors occur at
        // the endpoints of the eye and target depths. At a fixed factor the projection is linear.
        double minT = 1.0;
        double maxT = 0.0;
        for (int i = 0; i < 2; ++i) {
            final double from = i == 0 ? eye.min(axis) : eye.max(axis);
            for (int j = 0; j < 2; ++j) {
                final double to = j == 0 ? target.min(axis) : target.max(axis);
                final double t = (middle - from) / (to - from);
                minT = Math.min(minT, t);
                maxT = Math.max(maxT, t);
            }
        }
        final int u = (axis + 1) % 3;
        final int v = (axis + 2) % 3;
        return new Slice(plane, projectedMin(eye, target, u, minT, maxT), projectedMax(eye, target, u, minT, maxT),
            projectedMin(eye, target, v, minT, maxT), projectedMax(eye, target, v, minT, maxT));
    }

    private static int projectedMin(final Box eye, final Box target, final int axis, final double minT, final double maxT) {
        return (int)Math.floor(Math.min(lerp(eye.min(axis), target.min(axis), minT), lerp(eye.min(axis), target.min(axis), maxT)) - EDGE_MARGIN);
    }

    private static int projectedMax(final Box eye, final Box target, final int axis, final double minT, final double maxT) {
        return (int)Math.floor(Math.max(lerp(eye.max(axis), target.max(axis), minT), lerp(eye.max(axis), target.max(axis), maxT)) + EDGE_MARGIN);
    }

    private static double lerp(final double from, final double to, final double t) {
        return from + (to - from) * t;
    }

    static final class Capture {
        private final byte[] opaque;
        private final int[] ends;

        Capture(final byte[] opaque, final int[] ends) {
            this.opaque = opaque.clone();
            this.ends = ends.clone();
        }

        boolean fullyOccluded() {
            int start = 0;
            for (final int end : ends) {
                int cell = start;
                while (cell < end && opaque[cell] != 0) {
                    ++cell;
                }
                if (cell == end) {
                    return true;
                }
                start = end;
            }
            return false;
        }
    }
}
