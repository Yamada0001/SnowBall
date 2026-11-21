package org.snowballmc.snowball.pathfinding;

import java.util.Arrays;
import java.util.Objects;

/** Detached, bounded weighted A* for a single level of ordinary land nodes. No server types. */
public final class FlatPathSearch {
    public static final int RADIUS = 8;
    public static final int SIDE = 2 * RADIUS + 1;
    public static final int CELLS = SIDE * SIDE;
    public static final int START = RADIUS * SIDE + RADIUS;

    // Vanilla WalkNodeEvaluator visits N/E/S/W, then NE/SE/SW/NW.
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};
    private static final float DIAGONAL = (float) Math.sqrt(2.0);

    private FlatPathSearch() {
    }

    public record Input(boolean[] walkable, int targetX, int targetZ, int reachRange, float maxLength, int maxVisitedNodes) {
        public Input {
            if (walkable.length != CELLS || !walkable[START] || targetX < -RADIUS || targetX > RADIUS
                || targetZ < -RADIUS || targetZ > RADIUS || reachRange < 0 || !Float.isFinite(maxLength) || maxLength <= 0
                || maxVisitedNodes <= 1) {
                throw new IllegalArgumentException("Invalid flat path snapshot");
            }
            walkable = walkable.clone();
        }

        @Override
        public boolean[] walkable() {
            return this.walkable.clone();
        }
    }

    public record Step(int x, int z, float g, float h, float f, float walkedDistance, boolean closed, int heapIndex) {
    }

    public record Result(Step[] steps) {
        public Result {
            steps = steps.clone();
        }

        @Override
        public Step[] steps() {
            return this.steps.clone();
        }
    }

    // A failed, interrupted, or edge-truncated search never produces a partial replacement path.
    static Result search(final Input input) {
        final float[] walked = new float[CELLS];
        final float[] cost = new float[CELLS];
        final float[] heuristic = new float[CELLS];
        final float[] score = new float[CELLS];
        final boolean[] closed = new boolean[CELLS];
        final int[] previous = new int[CELLS];
        Arrays.fill(previous, -1);
        final Heap open = new Heap(score);
        heuristic[START] = distance(0, 0, input.targetX, input.targetZ);
        score[START] = heuristic[START];
        open.put(START, score[START]);
        int best = START;
        float bestDistance = heuristic[START];
        for (int count = 1; open.size != 0 && count < input.maxVisitedNodes; count++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            final int current = open.pop();
            closed[current] = true;
            final int x = current % SIDE - RADIUS;
            final int z = current / SIDE - RADIUS;
            if (Math.abs(input.targetX - x) + Math.abs(input.targetZ - z) <= input.reachRange) {
                int length = 0;
                for (int step = best; step >= 0; step = previous[step]) {
                    length++;
                }
                final Step[] steps = new Step[length];
                for (int step = best, i = length - 1; step >= 0; step = previous[step], i--) {
                    steps[i] = new Step(step % SIDE - RADIUS, step / SIDE - RADIUS, cost[step], heuristic[step], score[step], walked[step], closed[step], open.indices[step]);
                }
                // Vanilla reconstructs from the closest discovered node rather than necessarily the popped node.
                return new Result(steps);
            }
            if (distance(x, z, 0, 0) >= input.maxLength) {
                continue;
            }
            if (Math.abs(x) == RADIUS || Math.abs(z) == RADIUS) {
                return null;
            }
            for (int direction = 0; direction < 8; direction++) {
                final int first = direction & 3;
                final boolean diagonal = direction >= 4;
                final int second = (first + 1) & 3;
                int nx = x + DX[first];
                int nz = z + DZ[first];
                if (diagonal) {
                    if (!input.walkable[index(nx, nz)] || !input.walkable[index(x + DX[second], z + DZ[second])]) {
                        continue;
                    }
                    nx += DX[second];
                    nz += DZ[second];
                }
                final int neighbor = index(nx, nz);
                if (!input.walkable[neighbor] || closed[neighbor]) {
                    continue;
                }
                final float edge = diagonal ? DIAGONAL : 1.0F;
                walked[neighbor] = walked[current] + edge;
                final float candidate = cost[current] + edge;
                if (walked[neighbor] < input.maxLength && (open.indices[neighbor] < 0 || candidate < cost[neighbor])) {
                    previous[neighbor] = current;
                    cost[neighbor] = candidate;
                    final float distance = distance(nx, nz, input.targetX, input.targetZ);
                    if (distance < bestDistance) {
                        best = neighbor;
                        bestDistance = distance;
                    }
                    heuristic[neighbor] = distance * 1.5F;
                    open.put(neighbor, candidate + heuristic[neighbor]);
                }
            }
        }
        return null;
    }

    private static int index(final int x, final int z) {
        return (z + RADIUS) * SIDE + x + RADIUS;
    }

    private static float distance(final int x1, final int z1, final int x2, final int z2) {
        final float dx = x1 - x2;
        final float dz = z1 - z2;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /** Indexed heap with vanilla's strict comparisons and right-child tie break. */
    private static final class Heap {
        private final int[] items = new int[CELLS];
        private final int[] indices = new int[CELLS];
        private final float[] score;
        private int size;

        private Heap(final float[] score) {
            this.score = Objects.requireNonNull(score);
            Arrays.fill(this.indices, -1);
        }

        private void put(final int node, final float value) {
            final int oldIndex = this.indices[node];
            final float oldScore = this.score[node];
            this.score[node] = value;
            if (oldIndex < 0) {
                this.up(this.size++, node);
            } else if (value < oldScore) {
                this.up(oldIndex, node);
            } else {
                this.down(oldIndex, node);
            }
        }

        private int pop() {
            final int node = this.items[0];
            final int last = this.items[--this.size];
            if (this.size != 0) {
                this.down(0, last);
            }
            this.indices[node] = -1;
            return node;
        }

        private void up(int slot, final int node) {
            while (slot > 0) {
                final int parent = (slot - 1) >>> 1;
                if (!(this.score[node] < this.score[this.items[parent]])) {
                    break;
                }
                this.assign(slot, this.items[parent]);
                slot = parent;
            }
            this.assign(slot, node);
        }

        private void down(int slot, final int node) {
            for (int left = 2 * slot + 1; left < this.size; left = 2 * slot + 1) {
                final int right = left + 1;
                final int cheaper = right == this.size || this.score[this.items[left]] < this.score[this.items[right]] ? left : right;
                if (!(this.score[this.items[cheaper]] < this.score[node])) {
                    break;
                }
                this.assign(slot, this.items[cheaper]);
                slot = cheaper;
            }
            this.assign(slot, node);
        }

        private void assign(final int slot, final int node) {
            this.items[slot] = node;
            this.indices[node] = slot;
        }
    }
}
