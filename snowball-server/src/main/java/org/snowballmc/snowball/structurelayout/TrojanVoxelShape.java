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

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 只承载 {@link BoxOctree} 的「伪 {@link VoxelShape}」，用作自由空间的标记对象。
 *
 * <p>它出现在原版存放自由空间 {@code VoxelShape} 的位置上，因此不需要改动任何字段类型；
 * 桥接层通过 {@code instanceof} 把它和真正的形状区分开。</p>
 *
 * <p><b>绝不能</b>把它交给任何真正的形状运算：</p>
 * <ul>
 *   <li>{@link #getCoords} 直接抛 {@link UnsupportedOperationException}：任何坐标查询都会立刻暴露误用，
 *       而不是给出无意义的结果；</li>
 *   <li>Paper(Moonrise) 给 {@code VoxelShape} 加了 {@code cachedShapeData} 等缓存字段，
 *       只有 {@code CubeVoxelShape}/{@code ArrayVoxelShape}/{@code SliceShape} 的构造器会调用
 *       {@code moonrise$initCache()} 填充它。本类不调用，所以缓存字段为 {@code null}；
 *       把本对象传进 {@code CollisionUtil.isJoinNonEmpty} 一类 API 时，
 *       {@code isEmpty()} 会读到未初始化的 {@code false}，随后退化成对一个空
 *       {@code DiscreteVoxelShape} 求值，<b>静默得到错误结果</b>而非抛异常。</li>
 * </ul>
 *
 * <p>因此 {@code JigsawPlacement} 中所有会消费自由空间的语句都必须改为
 * {@link StructureLayoutBridge} 调用，由桥接层先做 {@code instanceof} 判定再决定走向。</p>
 */
public class TrojanVoxelShape extends VoxelShape {

    public final BoxOctree boxOctree;

    public TrojanVoxelShape(final BoxOctree boxOctree) {
        // 0×0×0 的空位图：不分配任何 bit，也不参与任何真实形状运算。
        super(BitSetDiscreteVoxelShape.withFilledBounds(0, 0, 0, 0, 0, 0, 0, 0, 0));
        this.boxOctree = boxOctree;
    }

    @Override
    public DoubleList getCoords(final Direction.Axis axis) {
        throw new UnsupportedOperationException("TrojanVoxelShape carries no voxel data, use StructureLayoutBridge instead");
    }
}
