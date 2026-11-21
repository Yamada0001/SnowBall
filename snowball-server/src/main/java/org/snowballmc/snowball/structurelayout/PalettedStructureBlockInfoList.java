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

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;

/**
 * 以调色板方式紧凑存储的 {@code StructureBlockInfo} 列表。
 *
 * <p>结构 NBT 中大量方块共享同一 {@code BlockState}/{@code CompoundTag}，因此把
 * (x, y, z, stateIndex, nbtIndex) 打包进 {@code long[]}，可显著降低模板常驻内存。
 * 对外仍表现为普通 {@code List<StructureBlockInfo>}：任何读取方法都会还原出等价的
 * {@code StructureBlockInfo} 对象，<b>元素顺序与原列表一致</b>。</p>
 *
 * <p><b>只读。</b>所有写方法（{@code set/add/remove/clear/...}）都抛
 * {@link UnsupportedOperationException}；若将来有插件通过反射修改
 * {@code StructureTemplate.Palette}，会在此处快速失败而不是静默损坏数据。</p>
 *
 * <p>调色板实现改写自 Glacier：
 * <a href="https://github.com/KingContaria/glacier/blob/main/src/main/java/me/contaria/glacier/optimization/memory/structure_block_infos/GlacierStructureBlockInfoList.java#L26">GlacierStructureBlockInfoList</a>。</p>
 */
public class PalettedStructureBlockInfoList implements List<StructureTemplate.StructureBlockInfo> {
    private static final long[] EMPTY_DATA = new long[0];
    private static final CompoundTag[] NULL_TAGS = new CompoundTag[]{null};
    private static final String UNSUPPORTED_OPERATION_ERROR_MESSAGE = "StructureTemplate.Palette 的方块列表是只读的紧凑表示，不支持修改";

    protected final long[] data;
    protected final BlockState[] states;
    protected final CompoundTag[] nbts;
    protected final int xBits, yBits, zBits;
    protected final int stateBits, nbtBits;
    protected final int bitsPerEntry;
    protected final int size;
    private WeakReference<List<StructureTemplate.StructureBlockInfo>> cachedStructureBlockInfoList = new WeakReference<>(null);

    public PalettedStructureBlockInfoList(final List<StructureTemplate.StructureBlockInfo> infos) {
        this(infos, null);
    }

    public PalettedStructureBlockInfoList(final List<StructureTemplate.StructureBlockInfo> infos,
                                          final Predicate<StructureTemplate.StructureBlockInfo> predicate) {
        List<Entry> entries = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<CompoundTag> tags = new ArrayList<>();

        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;

        for (StructureTemplate.StructureBlockInfo info : infos) {
            if (predicate != null && !predicate.test(info)) {
                continue;
            }

            int state = states.indexOf(info.state());
            if (state == -1) {
                state = states.size();
                states.add(info.state());
            }

            int tag = indexOf(tags, info.nbt());
            if (tag == -1) {
                tag = tags.size();
                tags.add(info.nbt());
            }

            int x = info.pos().getX();
            int y = info.pos().getY();
            int z = info.pos().getZ();

            if (x < 0 || y < 0 || z < 0) {
                throw new IllegalArgumentException("非法的 StructureBlockInfo 坐标: " + info.pos());
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y > maxY) {
                maxY = y;
            }
            if (z > maxZ) {
                maxZ = z;
            }

            entries.add(new Entry(x, y, z, state, tag));
        }

        this.xBits = bits(maxX);
        this.yBits = bits(maxY);
        this.zBits = bits(maxZ);
        this.stateBits = bits(states.size() - 1);
        this.nbtBits = bits(tags.size() - 1);
        this.bitsPerEntry = this.xBits + this.yBits + this.zBits + this.stateBits + this.nbtBits;

        if (this.bitsPerEntry > 64) {
            throw new IllegalArgumentException("每个方块条目占用位数过多: " + this.bitsPerEntry);
        }

        this.size = entries.size();
        if (this.bitsPerEntry != 0) {
            int entriesPerLong = 64 / this.bitsPerEntry;
            this.data = new long[(this.size + entriesPerLong - 1) / entriesPerLong];
            for (int i = 0; i < this.size; i++) {
                this.data[i / entriesPerLong] |= entries.get(i).compress(this.xBits, this.yBits, this.zBits, this.stateBits) << ((i % entriesPerLong) * this.bitsPerEntry);
            }
        } else {
            this.data = EMPTY_DATA;
        }

        this.states = states.toArray(new BlockState[0]);
        this.nbts = tags.size() == 1 && tags.getFirst() == null ? NULL_TAGS : tags.toArray(new CompoundTag[0]);
    }

    private static int bits(final int i) {
        int bits = 0;
        while (i >= 1 << bits) {
            bits++;
        }

        return bits;
    }

    private static <T> int indexOf(final List<T> list, final T o) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == o) {
                return i;
            }
        }

        return -1;
    }

    private List<StructureTemplate.StructureBlockInfo> convertBackToStructureBlockInfoListAndCache() {
        synchronized (this.data) {
            List<StructureTemplate.StructureBlockInfo> structureBlockInfos = this.cachedStructureBlockInfoList.get();
            if (structureBlockInfos != null) {
                return structureBlockInfos;
            }

            structureBlockInfos = new ObjectArrayList<>(new PalettedStructureBlockInfoListIterator(this));
            this.cachedStructureBlockInfoList = new WeakReference<>(structureBlockInfos);
            return structureBlockInfos;
        }
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        return this.size == 0;
    }

    @NotNull
    @Override
    public Iterator<StructureTemplate.StructureBlockInfo> iterator() {
        return convertBackToStructureBlockInfoListAndCache().iterator();
    }

    @NotNull
    @Override
    public ListIterator<StructureTemplate.StructureBlockInfo> listIterator() {
        return convertBackToStructureBlockInfoListAndCache().listIterator();
    }

    @NotNull
    @Override
    public ListIterator<StructureTemplate.StructureBlockInfo> listIterator(final int index) {
        return convertBackToStructureBlockInfoListAndCache().listIterator(index);
    }

    @Override
    public boolean contains(final Object o) {
        return convertBackToStructureBlockInfoListAndCache().contains(o);
    }

    @Override
    public boolean containsAll(@NotNull final Collection<?> c) {
        return new HashSet<>(convertBackToStructureBlockInfoListAndCache()).containsAll(c);
    }

    @NotNull
    @Override
    public Object @NotNull [] toArray() {
        return convertBackToStructureBlockInfoListAndCache().toArray();
    }

    @NotNull
    @Override
    public <T> T @NotNull [] toArray(@NotNull final T @NotNull [] a) {
        return convertBackToStructureBlockInfoListAndCache().toArray(a);
    }

    @Override
    public StructureTemplate.StructureBlockInfo get(final int index) {
        return convertBackToStructureBlockInfoListAndCache().get(index);
    }

    @Override
    public int indexOf(final Object o) {
        return convertBackToStructureBlockInfoListAndCache().indexOf(o);
    }

    @Override
    public int lastIndexOf(final Object o) {
        return convertBackToStructureBlockInfoListAndCache().lastIndexOf(o);
    }

    @NotNull
    @Override
    public List<StructureTemplate.StructureBlockInfo> subList(final int fromIndex, final int toIndex) {
        return convertBackToStructureBlockInfoListAndCache().subList(fromIndex, toIndex);
    }

    @Override
    public StructureTemplate.StructureBlockInfo set(final int index, final StructureTemplate.StructureBlockInfo element) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public void add(final int index, final StructureTemplate.StructureBlockInfo element) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public boolean add(final StructureTemplate.StructureBlockInfo info) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public boolean remove(final Object o) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public StructureTemplate.StructureBlockInfo remove(final int index) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public boolean addAll(@NotNull final Collection<? extends StructureTemplate.StructureBlockInfo> c) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public boolean addAll(final int index, @NotNull final Collection<? extends StructureTemplate.StructureBlockInfo> c) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public boolean removeAll(@NotNull final Collection<?> c) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public boolean retainAll(@NotNull final Collection<?> c) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_ERROR_MESSAGE);
    }

    private record Entry(int x, int y, int z, int state, int nbt) {

        private long compress(final int xBits, final int yBits, final int zBits, final int stateBits) {
            return this.x + ((this.y + ((this.z + ((this.state + ((long) this.nbt << stateBits)) << zBits)) << yBits)) << xBits);
        }
    }
}
