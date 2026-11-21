package org.snowballmc.snowball.modernfix.bridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 定期清空 DataFixerUpper 的内部缓存。
 *
 * <p>DFU 在类型重写路径上放了几个**静态**缓存 map，一旦见过某个类型就永不释放。
 * 它们在启动期把转化规则全部预热，之后基本是纯内存占用。本类用一个后台守护线程，
 * 在没有数据转化的空闲期把这几张表清掉。</p>
 *
 * <p>字段全部按需反射：任一字段取不到（DFU 版本变动）只跳过该字段并记 WARN，
 * 绝不阻断服务器启动。</p>
 *
 * <p>移植自 ModernFix（LGPL-3.0）。</p>
 */
public final class DfuBlaster {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long IDLE_MS = 60_000L;
    private static final AtomicLong NEXT_WAKE = new AtomicLong(Long.MAX_VALUE);

    private static final List<Map<?, ?>> TRACKED_MAPS = new ArrayList<>(3);
    private static volatile boolean started;

    private DfuBlaster() {
    }

    /**
     * 收集 DFU 的内部缓存表并启动清理线程。构造 {@code DataFixers} 之后调用一次。
     */
    public static void blastMaps() {
        collect("com.mojang.serialization.codecs.BaseMapCodec$MapCodecBase", null);
        collect("com.mojang.datafixers.DSL$Instances", "TAGGED_CHOICE_TYPE_CACHE");
        collect("com.mojang.datafixers.functions.Fold", "HMAP_APPLY_CACHE");
        collect("com.mojang.datafixers.types.Type", "REWRITE_CACHE");

        NEXT_WAKE.set(System.currentTimeMillis() + IDLE_MS);
        if (!started && !TRACKED_MAPS.isEmpty()) {
            started = true;
            final Thread thread = new Thread(DfuBlaster::run, "Leaves-DFU-Blaster");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
    }

    /**
     * 有新的数据转化发生时调用，把清理时间顺延，避免在活跃期清表。
     */
    public static void kick() {
        NEXT_WAKE.set(System.currentTimeMillis() + IDLE_MS);
    }

    private static void run() {
        while (true) {
            try {
                Thread.sleep(5_000L);
                if (System.currentTimeMillis() < NEXT_WAKE.get()) {
                    continue;
                }
                for (final Map<?, ?> map : TRACKED_MAPS) {
                    try {
                        map.clear();
                    } catch (final Throwable ignored) {
                        // 单个表清理失败不该影响其余表
                    }
                }
                NEXT_WAKE.set(Long.MAX_VALUE);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Throwable throwable) {
                LOGGER.warn("Leaves: DFU cache blaster stopped unexpectedly", throwable);
                return;
            }
        }
    }

    private static void collect(final String className, final String fieldName) {
        try {
            final Class<?> owner = Class.forName(className);
            final Field field = fieldName == null ? firstMapField(owner) : owner.getDeclaredField(fieldName);
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            final Object value = field.get(null);
            if (value instanceof Map<?, ?> map) {
                TRACKED_MAPS.add(map);
            }
        } catch (final ClassNotFoundException | NoSuchFieldException | IllegalAccessException
                       | RuntimeException ignored) {
            // DFU 内部结构变化时静默跳过：这是纯优化路径，缺一张表不影响正确性
        }
    }

    private static Field firstMapField(final Class<?> owner) {
        for (final Field field : owner.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        return null;
    }
}
