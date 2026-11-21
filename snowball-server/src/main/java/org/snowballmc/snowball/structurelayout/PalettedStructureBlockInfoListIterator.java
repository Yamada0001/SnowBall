/*
 * This file is licensed under the MIT License, part of StructureLayoutOptimizer.
 * Copyright (c) 2024 TelepathicGrunt
 * Portions of this file are derived from Glacier
 * (https://github.com/KingContaria/glacier), MIT License, Copyright (c) KingContaria.
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Iterator;

/**
 * {@link PalettedStructureBlockInfoList} 的还原迭代器：按<b>插入顺序</b>把
 * {@code long[]} 调色板解回 {@code StructureBlockInfo}。
 *
 * <p>每次 {@link #next()} 都新建 {@code BlockPos}/{@code StructureBlockInfo} 实例，
 * 避免调用方持有并在别处复用同一个可变对象。</p>
 *
 * <p>实现改写自 Glacier 的同名类。</p>
 */
public class PalettedStructureBlockInfoListIterator implements Iterator<StructureTemplate.StructureBlockInfo> {
    private final PalettedStructureBlockInfoList infos;

    private final int xOffset, yOffset, zOffset, stateOffset;
    private final int xMask, yMask, zMask, stateMask, tagMask;

    private int index = 0;

    public PalettedStructureBlockInfoListIterator(final PalettedStructureBlockInfoList infos) {
        this.infos = infos;
        this.xOffset = infos.xBits;
        this.yOffset = this.xOffset + infos.yBits;
        this.zOffset = this.yOffset + infos.zBits;
        this.stateOffset = this.zOffset + infos.stateBits;
        this.xMask = (1 << infos.xBits) - 1;
        this.yMask = (1 << infos.yBits) - 1;
        this.zMask = (1 << infos.zBits) - 1;
        this.stateMask = (1 << infos.stateBits) - 1;
        this.tagMask = (1 << infos.nbtBits) - 1;
    }

    @Override
    public boolean hasNext() {
        return this.index < this.size();
    }

    @Override
    public StructureTemplate.StructureBlockInfo next() {
        int index = this.toIndex(this.index++);
        if (index >= this.infos.size) {
            throw new IndexOutOfBoundsException();
        }

        int bitsPerEntry = this.infos.bitsPerEntry;
        if (bitsPerEntry == 0) {
            // The position is guaranteed to be {0, 0, 0}
            // New object in case other mods save reference to it or store it in their own list.
            return new StructureTemplate.StructureBlockInfo(new BlockPos(0, 0, 0), this.infos.states[0], this.infos.nbts[0]);
        }

        int entriesPerLong = 64 / bitsPerEntry;
        long entry = this.infos.data[index / entriesPerLong] >>> ((index % entriesPerLong) * bitsPerEntry);

        int x = (int) (entry & this.xMask);
        int y = (int) (entry >> this.xOffset & this.yMask);
        int z = (int) (entry >> this.yOffset & this.zMask);
        int state = (int) (entry >> this.zOffset & this.stateMask);
        int tag = (int) (entry >> this.stateOffset & this.tagMask);

        // New object in case other mods save reference to it or store it in their own list.
        return new StructureTemplate.StructureBlockInfo(new BlockPos(x, y, z), this.infos.states[state], this.infos.nbts[tag]);
    }

    protected int toIndex(final int index) {
        return index;
    }

    protected int size() {
        return this.infos.size;
    }
}
