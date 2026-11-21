package org.snowballmc.snowball.bellows;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * {@link ValueOutput} 视图包装器：只有名字命中 {@code allowed} 的顶层键会真正写入 delegate，
 * 其余写入全部丢弃。
 *
 * <p>用途：让 {@code Entity#saveWithoutId} 完整跑一遍原版保存逻辑，但只把 datapack 请求的字段
 * 真正序列化进 {@link net.minecraft.world.level.storage.TagValueOutput}。
 *
 * <p>相比"复制一份 switch-case 版 save/load"，本包装器不会随上游保存逻辑改动而失同步，
 * 也不需要为每个实体子类单独打一个补丁。
 *
 * <p><b>粒度说明</b>：本类只做<b>顶层键</b>粒度。若请求 {@code Inventory[{Slot:3b}]}，整个
 * {@code Inventory} 仍会被序列化，随后由 {@code NbtPathArgument.NbtPath} 从中取值。
 * 这是有意的安全降级 —— 子路径级过滤需要理解每个字段的内部结构，会重新引入失同步风险。
 */
public final class FilteringValueOutput implements ValueOutput {

    private final ValueOutput delegate;
    private final Set<String> allowed;

    public FilteringValueOutput(final ValueOutput delegate, final Set<String> allowed) {
        this.delegate = delegate;
        this.allowed = allowed;
    }

    private boolean allow(final String name) {
        return this.allowed.contains(name);
    }

    @Override
    public <T> void store(final String name, final Codec<T> codec, final T value) {
        if (this.allow(name)) {
            this.delegate.store(name, codec, value);
        }
    }

    @Override
    public <T> void storeNullable(final String name, final Codec<T> codec, final @Nullable T value) {
        if (this.allow(name)) {
            this.delegate.storeNullable(name, codec, value);
        }
    }

    /**
     * 无名字版本，无法过滤，直接转发。
     *
     * <p>原版只在极少数 {@link MapCodec} 路径使用该方法；走这条路径的字段无法按名字过滤，
     * 结果会多写一点（语义仍然正确，只是收益打折）。
     */
    @Override
    @Deprecated
    public <T> void store(final MapCodec<T> codec, final T value) {
        this.delegate.store(codec, value);
    }

    @Override
    public void putBoolean(final String name, final boolean value) {
        if (this.allow(name)) {
            this.delegate.putBoolean(name, value);
        }
    }

    @Override
    public void putByte(final String name, final byte value) {
        if (this.allow(name)) {
            this.delegate.putByte(name, value);
        }
    }

    @Override
    public void putShort(final String name, final short value) {
        if (this.allow(name)) {
            this.delegate.putShort(name, value);
        }
    }

    @Override
    public void putInt(final String name, final int value) {
        if (this.allow(name)) {
            this.delegate.putInt(name, value);
        }
    }

    @Override
    public void putLong(final String name, final long value) {
        if (this.allow(name)) {
            this.delegate.putLong(name, value);
        }
    }

    @Override
    public void putFloat(final String name, final float value) {
        if (this.allow(name)) {
            this.delegate.putFloat(name, value);
        }
    }

    @Override
    public void putDouble(final String name, final double value) {
        if (this.allow(name)) {
            this.delegate.putDouble(name, value);
        }
    }

    @Override
    public void putString(final String name, final String value) {
        if (this.allow(name)) {
            this.delegate.putString(name, value);
        }
    }

    @Override
    public void putIntArray(final String name, final int[] value) {
        if (this.allow(name)) {
            this.delegate.putIntArray(name, value);
        }
    }

    @Override
    public ValueOutput child(final String name) {
        return this.allow(name) ? this.delegate.child(name) : Discarding.INSTANCE;
    }

    @Override
    public ValueOutput.ValueOutputList childrenList(final String name) {
        return this.allow(name) ? this.delegate.childrenList(name) : Discarding.EMPTY_LIST;
    }

    @Override
    public <T> ValueOutput.TypedOutputList<T> list(final String name, final Codec<T> codec) {
        return this.allow(name) ? this.delegate.list(name, codec) : Discarding.emptyTypedList();
    }

    @Override
    public void discard(final String name) {
        if (this.allow(name)) {
            this.delegate.discard(name);
        }
    }

    @Override
    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    /** 黑洞实现：接受一切写入但什么也不做。 */
    static final class Discarding implements ValueOutput {

        static final Discarding INSTANCE = new Discarding();

        @SuppressWarnings("unchecked")
        static <T> ValueOutput.TypedOutputList<T> emptyTypedList() {
            return (ValueOutput.TypedOutputList<T>) EMPTY_TYPED_LIST;
        }

        static final ValueOutput.TypedOutputList<Object> EMPTY_TYPED_LIST = new ValueOutput.TypedOutputList<>() {
            @Override
            public void add(final Object value) {
            }

            @Override
            public boolean isEmpty() {
                return true;
            }
        };

        static final ValueOutput.ValueOutputList EMPTY_LIST = new ValueOutput.ValueOutputList() {
            @Override
            public ValueOutput addChild() {
                return Discarding.INSTANCE;
            }

            @Override
            public void discardLast() {
            }

            @Override
            public boolean isEmpty() {
                return true;
            }
        };

        @Override
        public <T> void store(final String name, final Codec<T> codec, final T value) {
        }

        @Override
        public <T> void storeNullable(final String name, final Codec<T> codec, final @Nullable T value) {
        }

        @Override
        @Deprecated
        public <T> void store(final MapCodec<T> codec, final T value) {
        }

        @Override
        public void putBoolean(final String name, final boolean value) {
        }

        @Override
        public void putByte(final String name, final byte value) {
        }

        @Override
        public void putShort(final String name, final short value) {
        }

        @Override
        public void putInt(final String name, final int value) {
        }

        @Override
        public void putLong(final String name, final long value) {
        }

        @Override
        public void putFloat(final String name, final float value) {
        }

        @Override
        public void putDouble(final String name, final double value) {
        }

        @Override
        public void putString(final String name, final String value) {
        }

        @Override
        public void putIntArray(final String name, final int[] value) {
        }

        @Override
        public ValueOutput child(final String name) {
            return INSTANCE;
        }

        @Override
        public ValueOutput.ValueOutputList childrenList(final String name) {
            return EMPTY_LIST;
        }

        @Override
        public <T> ValueOutput.TypedOutputList<T> list(final String name, final Codec<T> codec) {
            @SuppressWarnings("unchecked")
            final ValueOutput.TypedOutputList<T> casted = (ValueOutput.TypedOutputList<T>) EMPTY_TYPED_LIST;
            return casted;
        }

        @Override
        public void discard(final String name) {
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    }
}
