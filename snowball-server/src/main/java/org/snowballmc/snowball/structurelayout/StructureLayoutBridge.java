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

import com.google.common.collect.Lists;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structure Layout Optimizer 的桥接门面。
 *
 * <p>补丁侧只做「原版语句 → 本类静态调用」的替换，所有算法、状态与分支都在本包内，
 * 补丁里不出现八叉树、去重、调色板等的具体逻辑。</p>
 *
 * <p><b>本类不读取配置</b>：是否启用由补丁侧读 {@code LeavesConfig.performance.*} 后传入或分支，
 * 便于单元测试与二进制一致性验证。关闭优化时，本类的每个方法都在内部回落到与
 * 原版逐字符等价的语句。</p>
 *
 * <p><b>重要约束：</b>{@link TrojanVoxelShape} 是纯标记对象，它的 {@code moonrise$initCache()}
 * 从未被调用（Paper 的 {@code VoxelShape} 缓存字段为 {@code null}），因此<b>绝不能</b>把它交给
 * {@code Shapes.join*}、{@code CollisionUtil.*} 或任何碰撞 API。本类所有接收 {@code VoxelShape}
 * 的方法都在内部先做 {@code instanceof TrojanVoxelShape} 判定，非标记对象一律回落到原版逻辑，
 * 从而保证「关闭优化」时逐位等价原版。</p>
 *
 * <p>移植自
 * <a href="https://github.com/TelepathicGrunt/StructureLayoutOptimizer">StructureLayoutOptimizer</a>（MIT）。</p>
 */
public final class StructureLayoutBridge {

    /**
     * 与原版 {@code SinglePoolElement.HIGHEST_SELECTION_PRIORITY_FIRST} 完全一致的比较器。
     */
    private static final Comparator<StructureTemplate.JigsawBlockInfo> HIGHEST_SELECTION_PRIORITY_FIRST =
        Comparator.comparingInt(StructureTemplate.JigsawBlockInfo::selectionPriority).reversed();

    private StructureLayoutBridge() {
    }

    // ------------------------------------------------------------------
    // 一、JigsawPlacement（八叉树自由空间）
    // ------------------------------------------------------------------

    /**
     * 构造「最大距离盒 − 中心拼图」的自由空间标记对象。
     *
     * <p>对应原版 {@code Shapes.join(Shapes.create(aabb), Shapes.create(AABB.of(box)), BooleanOp.ONLY_FIRST)}：
     * 边界＝{@code aabb}，已占位＝中心拼图的包围盒。</p>
     *
     * @param bounds    本次 jigsaw 生成允许的最大范围（原版局部变量 {@code aabb}）
     * @param centerBox 中心拼图的包围盒（原版局部变量 {@code box}）
     */
    public static VoxelShape createRootFreeShape(final AABB bounds, final BoundingBox centerBox) {
        TrojanVoxelShape shape = new TrojanVoxelShape(new BoxOctree(bounds));
        shape.boxOctree.addBox(AABB.of(centerBox));
        return shape;
    }

    /**
     * 为「拼图内部拼接」分支初始化 sourceFree。
     *
     * <p>原版为 {@code Shapes.create(AABB.of(sourceBB))}，即把整个 source 包围盒视为自由空间；
     * 优化路径改为「边界＝sourceBB、无占位盒」的八叉树。</p>
     */
    public static void initSourceFree(final MutableObject<VoxelShape> sourceFree, final BoundingBox sourceBB, final boolean optimized) {
        if (!optimized) {
            sourceFree.setValue(Shapes.create(AABB.of(sourceBB)));
            return;
        }

        sourceFree.setValue(new TrojanVoxelShape(new BoxOctree(AABB.of(sourceBB))));
    }

    /**
     * RIGID 候选拼图的目标接点在自由空间中已被占满时返回 {@code true}，调用方据此跳过本次
     * {@code getShuffledJigsawBlocks} 的结果（换成空列表继续走原版流程）。
     *
     * <p>语义与上游一致：接点不在边界内、或落在任一已占位盒内，即为「必然放不下」。
     * 对非标记形状（优化关闭）恒返回 {@code false}，即完全走原版逻辑。</p>
     */
    public static boolean isTargetPosBlocked(final VoxelShape childrenFree,
                                             final StructurePoolElement targetElement,
                                             final BlockPos targetJigsawPos) {
        if (!(childrenFree instanceof TrojanVoxelShape trojan)) {
            return false;
        }

        if (targetElement.getProjection() != StructureTemplatePool.Projection.RIGID) {
            return false;
        }

        BoxOctree octree = trojan.boxOctree;
        return !octree.boundaryContains(targetJigsawPos) || octree.withinAnyBox(targetJigsawPos);
    }

    /**
     * 替换原版
     * {@code !Shapes.joinIsNotEmpty(childrenFree.get(), Shapes.create(AABB.of(targetBB).deflate(0.25)), BooleanOp.ONLY_SECOND)}。
     *
     * <p>返回值即「允许放置」的最终判定：候选包围盒（内缩 0.25 以容忍贴面）必须完整落在自由空间内，
     * 且不与任何已占位盒相交。</p>
     */
    public static boolean canPlace(final VoxelShape childrenFree, final BoundingBox targetBB) {
        AABB probe = AABB.of(targetBB).deflate(0.25D);
        if (childrenFree instanceof TrojanVoxelShape trojan) {
            return trojan.boxOctree.withinBoundsButNotIntersectingChildren(probe);
        }

        return !Shapes.joinIsNotEmpty(childrenFree, Shapes.create(probe), BooleanOp.ONLY_SECOND);
    }

    /**
     * 替换原版
     * {@code childrenFree.setValue(Shapes.joinUnoptimized(childrenFree.get(), Shapes.create(AABB.of(targetBB)), BooleanOp.ONLY_FIRST))}。
     *
     * <p>优化路径不替换 {@link MutableObject} 持有的值（保持标记对象可复用），只把该片元登记为已占位。</p>
     */
    public static void markPlaced(final MutableObject<VoxelShape> childrenFree, final BoundingBox targetBB) {
        VoxelShape current = childrenFree.get();
        if (current instanceof TrojanVoxelShape trojan) {
            trojan.boxOctree.addBox(AABB.of(targetBB));
            return;
        }

        childrenFree.setValue(Shapes.joinUnoptimized(current, Shapes.create(AABB.of(targetBB)), BooleanOp.ONLY_FIRST));
    }

    // ------------------------------------------------------------------
    // 二、SinglePoolElement（拼图方块列表洗牌）
    // ------------------------------------------------------------------

    /**
     * 替换原版 {@code Util.shuffle(jigsaws, random)} + {@code sortBySelectionPriority(jigsaws)}。
     *
     * <p><b>与上游 mod 的有意偏离：</b>上游按 {@code selectionPriority} 分桶后逐桶洗牌再倒序拼接，
     * 当存在多个不同优先级时会比原版少消耗 {@code (#桶数 - 1)} 次 {@code nextInt}，
     * 从而让同一次结构生成后续的所有随机数整体错位、布局漂移。本实现改为：</p>
     * <ul>
     *   <li>全部元素同优先级（绝大多数数据集）→ 分桶等价于整体洗牌，直接复用原版
     *       {@link Util#shuffle(List, RandomSource)}，随机数消耗与结果顺序与原版逐位一致；</li>
     *   <li>存在多个优先级 → 完全走原版「整体洗牌 + 稳定排序」，绝不改变种子一致性。</li>
     * </ul>
     * <p>因此本方法在任何输入下都与原版<b>输出相同、随机数消耗相同</b>，只是省掉了常见情形下的
     * 一次整体排序。</p>
     */
    public static void shuffleJigsawsBySelectionPriority(final List<StructureTemplate.JigsawBlockInfo> jigsaws,
                                                         final RandomSource random) {
        int size = jigsaws.size();
        if (size < 2) {
            return;
        }

        // 先扫描是否存在多个不同优先级（O(n) 且通常首个不同就退出），再整体洗牌。
        int firstPriority = jigsaws.getFirst().selectionPriority();
        boolean uniformPriority = true;
        for (int i = 1; i < size; i++) {
            if (jigsaws.get(i).selectionPriority() != firstPriority) {
                uniformPriority = false;
                break;
            }
        }

        // 与 vanilla 完全相同的随机数消耗（size - 1 次 nextInt）与洗牌结果。
        Util.shuffle(jigsaws, random);
        if (uniformPriority) {
            // 全部同优先级：稳定排序是空操作，可安全省略。
            return;
        }

        // 多优先级：与原版一致地做稳定排序，保证顺序与 RNG 序列都不漂移。
        jigsaws.sort(HIGHEST_SELECTION_PRIORITY_FIRST);
    }

    // ------------------------------------------------------------------
    // 三、StructureTemplatePool（模板池去重）
    // ------------------------------------------------------------------

    /**
     * 替换 {@code pool.getShuffledTemplates(random)}；{@code deduplicate} 为 {@code true} 时按
     * 「首次出现顺序」去重（保留首次出现的元素）。
     *
     * <p>随机数在 {@link StructureTemplatePool#getShuffledTemplates} 内部已按原版消耗完毕，
     * 去重只影响元素列表，不改变随机数序列。</p>
     */
    public static List<StructurePoolElement> getShuffledTemplates(final StructureTemplatePool pool,
                                                                  final RandomSource random,
                                                                  final boolean deduplicate) {
        List<StructurePoolElement> shuffled = pool.getShuffledTemplates(random);
        if (!deduplicate) {
            return shuffled;
        }

        Set<StructurePoolElement> unique = new LinkedHashSet<>(pool.getTemplates().size());
        // 不要用 addAll：保持与上游一致的「逐个 add」实现，避免集合实现差异。
        for (StructurePoolElement element : shuffled) {
            unique.add(element);
        }

        List<StructurePoolElement> result = new ArrayList<>(unique.size());
        for (StructurePoolElement element : unique) {
            result.add(element);
        }

        return result;
    }

    /**
     * 替换 {@code Lists.newArrayList()} 生成 {@code targetPieces}。
     *
     * <p>去重开启时无需记录「已处理过的元素」，用普通 {@link ArrayList}；
     * 关闭时用 {@link TrojanArrayList} 记录本池已尝试过的元素，用于跳过同一元素重复的 4 次旋转检查。</p>
     */
    public static List<StructurePoolElement> newTargetPieceList(final boolean deduplicate) {
        return deduplicate ? Lists.newArrayList() : new TrojanArrayList<>();
    }

    /**
     * 替换 {@code Rotation.getShuffled(random)} 的返回值，必要时收缩为「本轮跳过」。
     *
     * <p>当同一元素在本模板池中已被完整尝试过 4 个旋转时，返回空列表以短路旋转循环；
     * 但必须先按原版顺序补做被跳过的随机数消耗（每个旋转一次 {@code getShuffledJigsawBlocks}），
     * 否则随机数序列会漂移。</p>
     *
     * @param shuffledRotations 原版 {@code Rotation.getShuffled(random)} 的结果
     * @return 用于 {@code for} 循环的旋转列表；空列表表示跳过该元素
     */
    public static List<Rotation> filterRotations(final List<Rotation> shuffledRotations,
                                                 final List<StructurePoolElement> targetPieces,
                                                 final StructurePoolElement targetElement,
                                                 final StructureTemplateManager structureTemplateManager,
                                                 final RandomSource random,
                                                 final boolean deduplicate) {
        if (deduplicate || !(targetPieces instanceof TrojanArrayList<?> trojanArrayList)) {
            return shuffledRotations;
        }

        if (trojanArrayList.elementsAlreadyParsed.contains(targetElement)) {
            // 补做被跳过的随机数消耗，保持与原版一致的 RNG 序列。
            for (Rotation rotation : shuffledRotations) {
                targetElement.getShuffledJigsawBlocks(structureTemplateManager, BlockPos.ZERO, rotation, random);
            }

            return List.of();
        }

        trojanArrayList.elementsAlreadyParsed.add(targetElement);
        return shuffledRotations;
    }

    // ------------------------------------------------------------------
    // 四、StructureTemplate.Palette（方块列表压缩）
    // ------------------------------------------------------------------

    /**
     * 把 {@code StructureTemplate.Palette} 的 {@code List<StructureBlockInfo>} 换成紧凑表示。
     *
     * <p>仅在 {@code blocks} 只被读取时安全；访问时会还原成等价的 {@code StructureBlockInfo} 列表，
     * 元素顺序与原始列表一致。任何写操作（{@code set/add/remove/clear}）都会抛
     * {@link UnsupportedOperationException}。</p>
     */
    public static List<StructureTemplate.StructureBlockInfo> compressPalette(final List<StructureTemplate.StructureBlockInfo> blocks) {
        if (blocks.isEmpty() || blocks instanceof PalettedStructureBlockInfoList) {
            return blocks;
        }

        return new PalettedStructureBlockInfoList(blocks);
    }
}
