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

import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 带「已解析元素」记录的 {@link ArrayList}。
 *
 * <p>仅用于 {@code JigsawPlacement.Placer#tryPlacingChildren} 的 {@code targetPieces}：
 * 同一模板池里同一个元素会按权重重复出现，而每个元素都要试 4 个旋转。
 * 记录已完整尝试过的元素后，后续重复项可以整体跳过旋转循环。</p>
 *
 * <p>跳过时<b>必须</b>补做被省略的随机数消耗，否则同一次生成的后续随机数会整体错位，
 * 结构布局随之漂移。补做逻辑在 {@link StructureLayoutBridge#filterRotations}。</p>
 */
public class TrojanArrayList<E> extends ArrayList<E> {

    /**
     * 本池中已经完整跑过 4 个旋转检查的元素。
     */
    public final Set<StructurePoolElement> elementsAlreadyParsed = new HashSet<>();
}
