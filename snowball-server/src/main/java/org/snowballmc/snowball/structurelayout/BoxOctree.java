/*
 * This file is licensed under the MIT License, part of StructureLayoutOptimizer.
 * Copyright (c) 2024 TelepathicGrunt
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.snowballmc.snowball.structurelayout;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 八叉树形式的「自由空间」。
 *
 * <p>原版用 {@code VoxelShape} 的并集/差集表示拼图生成过程中剩余的可放置空间，
 * 每次判定与登记都要重建形状对象，复杂度随已放元素数量线性增长。本类改为
 * 「一个边界 AABB + 一组已占位 AABB」的层级结构：</p>
 * <ul>
 *   <li>{@link #boundary}：本次生成允许的最大范围（原版 {@code aabb}）；</li>
 *   <li>{@link #innerBoxes}：已占位的包围盒；超过阈值后分裂为 8 个子八叉树。</li>
 * </ul>
 *
 * <p>语义上与 {@code Shapes.join(Shapes.create(aabb), ..., BooleanOp.ONLY_FIRST)} 一类的
 * 形状运算等价：查询「probe 是否完整落在自由空间内」即
 * {@link #withinBoundsButNotIntersectingChildren(AABB)}。</p>
 *
 * <p><b>非线程安全。</b>生命周期只限于一次 {@code JigsawPlacement#addPieces}（单线程），
 * 不要把它提升为静态字段或跨生成任务复用。</p>
 *
 * <p>移植自
 * <a href="https://github.com/TelepathicGrunt/StructureLayoutOptimizer">StructureLayoutOptimizer</a>（MIT）。</p>
 */
public class BoxOctree {

    /**
     * 单个节点在分裂前允许累积的占位盒数量。
     */
    private static final int SUBDIVIDE_THRESHOLD = 10;

    /**
     * 八叉树最大深度。超过后不再分裂，退化为止于该层的线性扫描。
     */
    private static final int MAXIMUM_DEPTH = 3;

    private final AABB boundary;
    private final Vec3i size;
    private final int depth;
    private final List<AABB> innerBoxes = new ArrayList<>();
    private final List<BoxOctree> childrenOctants = new ArrayList<>();

    public BoxOctree(final AABB axisAlignedBB) {
        this(axisAlignedBB, 0);
    }

    private BoxOctree(final AABB axisAlignedBB, final int parentDepth) {
        this.boundary = axisAlignedBB.move(0, 0, 0); // deep copy
        this.size = new Vec3i(
            roundAwayFromZero(this.boundary.getXsize()),
            roundAwayFromZero(this.boundary.getYsize()),
            roundAwayFromZero(this.boundary.getZsize())
        );
        this.depth = parentDepth + 1;
    }

    private static int roundAwayFromZero(final double value) {
        return (value >= 0) ? (int) Math.ceil(value) : (int) Math.floor(value);
    }

    private void subdivide() {
        if (!this.childrenOctants.isEmpty()) {
            throw new IllegalStateException("Tried to subdivide a BoxOctree node that already has children.");
        }

        int halfXSize = this.size.getX() / 2;
        int halfYSize = this.size.getY() / 2;
        int halfZSize = this.size.getZ() / 2;

        // Lower Left Back Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX, this.boundary.minY, this.boundary.minZ,
            this.boundary.minX + halfXSize, this.boundary.minY + halfYSize, this.boundary.minZ + halfZSize),
            this.depth));

        // Lower Left Front Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX, this.boundary.minY, this.boundary.minZ + halfZSize,
            this.boundary.minX + halfXSize, this.boundary.minY + halfYSize, this.boundary.maxZ),
            this.depth));

        // Lower Right Back Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX + halfXSize, this.boundary.minY, this.boundary.minZ,
            this.boundary.maxX, this.boundary.minY + halfYSize, this.boundary.minZ + halfZSize),
            this.depth));

        // Lower Right Front Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX + halfXSize, this.boundary.minY, this.boundary.minZ + halfZSize,
            this.boundary.maxX, this.boundary.minY + halfYSize, this.boundary.maxZ),
            this.depth));

        // Upper Left Back Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX, this.boundary.minY + halfYSize, this.boundary.minZ,
            this.boundary.minX + halfXSize, this.boundary.maxY, this.boundary.minZ + halfZSize),
            this.depth));

        // Upper Left Front Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX, this.boundary.minY + halfYSize, this.boundary.minZ + halfZSize,
            this.boundary.minX + halfXSize, this.boundary.maxY, this.boundary.maxZ),
            this.depth));

        // Upper Right Back Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX + halfXSize, this.boundary.minY + halfYSize, this.boundary.minZ,
            this.boundary.maxX, this.boundary.maxY, this.boundary.minZ + halfZSize),
            this.depth));

        // Upper Right Front Corner
        this.childrenOctants.add(new BoxOctree(new AABB(
            this.boundary.minX + halfXSize, this.boundary.minY + halfYSize, this.boundary.minZ + halfZSize,
            this.boundary.maxX, this.boundary.maxY, this.boundary.maxZ),
            this.depth));

        for (AABB parentInnerBox : this.innerBoxes) {
            for (BoxOctree octree : this.childrenOctants) {
                if (octree.boundaryIntersects(parentInnerBox)) {
                    octree.addBox(parentInnerBox);
                }
            }
        }

        this.innerBoxes.clear();
    }

    public void addBox(final AABB axisAlignedBB) {
        if (this.depth < MAXIMUM_DEPTH && this.innerBoxes.size() > SUBDIVIDE_THRESHOLD) {
            this.subdivide();
        }

        if (!this.childrenOctants.isEmpty()) {
            for (BoxOctree octree : this.childrenOctants) {
                if (octree.boundaryIntersects(axisAlignedBB)) {
                    octree.addBox(axisAlignedBB);
                }
            }
        } else {
            // Prevent re-adding the same box if it already exists
            for (AABB parentInnerBox : this.innerBoxes) {
                if (parentInnerBox.equals(axisAlignedBB)) {
                    return;
                }
            }

            this.innerBoxes.add(axisAlignedBB);
        }
    }

    public boolean boundaryEntirelyContains(final AABB axisAlignedBB) {
        return this.boundary.contains(axisAlignedBB.minX, axisAlignedBB.minY, axisAlignedBB.minZ)
            && this.boundary.contains(axisAlignedBB.maxX, axisAlignedBB.maxY, axisAlignedBB.maxZ);
    }

    public boolean boundaryIntersects(final AABB axisAlignedBB) {
        return this.boundary.intersects(axisAlignedBB);
    }

    public boolean withinBoundsButNotIntersectingChildren(final AABB axisAlignedBB) {
        return this.boundaryEntirelyContains(axisAlignedBB) && !this.intersectsAnyBox(axisAlignedBB);
    }

    public boolean intersectsAnyBox(final AABB axisAlignedBB) {
        if (!this.childrenOctants.isEmpty()) {
            for (BoxOctree octree : this.childrenOctants) {
                if (octree.boundaryIntersects(axisAlignedBB) && octree.intersectsAnyBox(axisAlignedBB)) {
                    return true;
                }
            }
        } else {
            for (AABB innerBox : this.innerBoxes) {
                if (innerBox.intersects(axisAlignedBB)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean boundaryContains(final BlockPos position) {
        return this.boundary.contains(position.getX(), position.getY(), position.getZ());
    }

    public boolean withinAnyBox(final BlockPos position) {
        if (!this.childrenOctants.isEmpty()) {
            for (BoxOctree octree : this.childrenOctants) {
                if (octree.boundaryContains(position) && octree.withinAnyBox(position)) {
                    return true;
                }
            }
        } else {
            for (AABB innerBox : this.innerBoxes) {
                if (innerBox.contains(position.getX(), position.getY(), position.getZ())) {
                    return true;
                }
            }
        }

        return false;
    }
}
