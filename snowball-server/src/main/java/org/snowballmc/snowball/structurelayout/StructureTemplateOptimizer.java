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
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code StructureTemplate#placeInWorld} 的方块列表预筛。
 *
 * <p>原版会对调色板里的<b>全部</b>方块做一次坐标变换与边界判定；本类提前只保留落在
 * {@code StructurePlaceSettings#getBoundingBox()} 内的方块，从而减少后续
 * {@code processBlockInfos} 与处理器链的调用量。</p>
 *
 * <p>与上游 mod 的差异：</p>
 * <ul>
 *   <li>用 {@link ClassValue} 取代上游自带的两千余行 {@code ConcurrentReferenceHashMap}：
 *       处理器类来自 NMS，不存在类加载器泄漏，{@code ClassValue} 更轻且无锁竞争；</li>
 *   <li>额外对 {@link StructureProcessor#evaluatesEntirePieceState()} 为 {@code true} 的处理器
 *       直接回退到完整列表——这类处理器（以及覆写 {@code finalizeProcessing} 的处理器）
 *       需要看到整个片元的方块，裁剪会导致结构残缺。</li>
 * </ul>
 *
 * <p>移植自
 * <a href="https://github.com/TelepathicGrunt/StructureLayoutOptimizer">StructureLayoutOptimizer</a>（MIT）。</p>
 */
public final class StructureTemplateOptimizer {

    /**
     * 每个处理器类是否需要「整片元」的方块列表。{@code true} 表示不可裁剪。
     */
    private static final ClassValue<Boolean> NEEDS_ENTIRE_PIECE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            return requiresFullBlockList(type);
        }
    };

    private StructureTemplateOptimizer() {
    }

    /**
     * 返回落在放置边界内的方块子列表；任何无法安全裁剪的情形都原样返回完整列表。
     *
     * <p>注意：即使结果为空且原始列表非空，也会保留首个元素。原版 {@code placeInWorld} 在
     * 方块列表为空时返回 {@code false}，会把这个片元从 structure start 中剔除，
     * 导致它本应写入其他区块的部分一起丢失。</p>
     */
    public static @NotNull List<StructureTemplate.StructureBlockInfo> getStructureBlockInfosInBounds(
        final StructureTemplate.Palette palette,
        final BlockPos offset,
        final StructurePlaceSettings settings
    ) {
        BoundingBox boundingBox = settings.getBoundingBox();
        List<StructureTemplate.StructureBlockInfo> originalPositions = palette.blocks();
        if (boundingBox == null) {
            return originalPositions;
        }

        for (StructureProcessor processor : settings.getProcessors()) {
            if (processor.evaluatesEntirePieceState() || NEEDS_ENTIRE_PIECE.get(processor.getClass())) {
                return originalPositions;
            }
        }

        Mirror mirror = settings.getMirror();
        Rotation rotation = settings.getRotation();
        BlockPos pivot = settings.getRotationPivot();

        List<StructureTemplate.StructureBlockInfo> inBounds = new ArrayList<>();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        for (StructureTemplate.StructureBlockInfo blockInfo : originalPositions) {
            mutableBlockPos.set(blockInfo.pos());
            transform(mutableBlockPos, mirror, rotation, pivot);
            mutableBlockPos.move(offset);

            if (boundingBox.isInside(mutableBlockPos)) {
                inBounds.add(blockInfo);
            }
        }

        if (inBounds.isEmpty() && !originalPositions.isEmpty()) {
            inBounds.add(originalPositions.getFirst());
        }

        return inBounds;
    }

    /**
     * 判断处理器是否覆写了 {@code StructureProcessor#finalizeProcessing}。
     *
     * <p>该方法在 26.3 的签名为
     * {@code (ServerLevelAccessor, BlockPos, BlockPos, List, List, StructurePlaceSettings)}，
     * 与上游 mod 反射的签名一致。{@link StructureProcessor} 自身的默认实现返回入参，
     * 因此「声明类不是 {@code StructureProcessor}」即代表被覆写。</p>
     */
    private static boolean requiresFullBlockList(final Class<?> processorClass) {
        try {
            Method method = processorClass.getMethod(
                "finalizeProcessing",
                ServerLevelAccessor.class,
                BlockPos.class,
                BlockPos.class,
                List.class,
                List.class,
                StructurePlaceSettings.class
            );
            return method.getDeclaringClass() != StructureProcessor.class;
        } catch (NoSuchMethodException e) {
            // 找不到方法说明上游改了签名：保守起见按「需要完整列表」处理，绝不裁剪。
            return true;
        }
    }

    /**
     * 与 {@code StructureTemplate.transform(BlockPos, Mirror, Rotation, BlockPos)} 等价的原地版本。
     *
     * <p>26.3 的实现逐行核对一致：镜像先取负、再按旋转绕 pivot 计算；{@code NONE} 且未镜像时保留原坐标。</p>
     */
    private static void transform(final BlockPos.MutableBlockPos mutableBlockPos,
                                  final Mirror mirror,
                                  final Rotation rotation,
                                  final BlockPos pivot) {
        int x = mutableBlockPos.getX();
        int y = mutableBlockPos.getY();
        int z = mutableBlockPos.getZ();
        boolean wasMirrored = true;
        switch (mirror) {
            case LEFT_RIGHT:
                z = -z;
                break;
            case FRONT_BACK:
                x = -x;
                break;
            default:
                wasMirrored = false;
        }

        int pivotX = pivot.getX();
        int pivotZ = pivot.getZ();
        switch (rotation) {
            case COUNTERCLOCKWISE_90:
                mutableBlockPos.set(pivotX - pivotZ + z, y, pivotX + pivotZ - x);
                return;
            case CLOCKWISE_90:
                mutableBlockPos.set(pivotX + pivotZ - z, y, pivotZ - pivotX + x);
                return;
            case CLOCKWISE_180:
                mutableBlockPos.set(pivotX + pivotX - x, y, pivotZ + pivotZ - z);
                return;
            default:
                if (wasMirrored) {
                    mutableBlockPos.set(x, y, z);
                }
        }
    }
}
