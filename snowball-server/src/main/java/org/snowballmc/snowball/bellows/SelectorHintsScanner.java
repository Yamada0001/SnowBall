package org.snowballmc.snowball.bellows;

import com.mojang.brigadier.StringReader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 从原始 {@code @e[...]} 文本里扫出可建索引的约束。
 *
 * <p><b>为什么要"扫原文"而不是钩 selector option 的 lambda：</b>
 * 上游模组用 {@code @Inject(method = "lambda$bootStrap$35")} 这类锚点定位 {@code type=}、
 * {@code tag=} 选项。这些是 javac 生成的数值序号：
 * <ul>
 *   <li>26.3 把 {@code tag} 选项内联进 {@code register("tag", parser -> ...)}，已不再是独立 lambda；</li>
 *   <li>{@code type} 选项的 lambda 内含<b>两处</b> {@code addPredicate}（tag 分支 / element 分支）；</li>
 *   <li>Paper/Leaves 补丁新增 lambda 会让序号整体漂移。</li>
 * </ul>
 * 因此序号锚点必然失配。改为解析原文后，补丁只需要挂在稳定的 {@code parse()} 上，
 * 不依赖任何字节码序号，也不依赖 {@code @Local(name = ...)} 所需的调试信息。
 *
 * <p>扫描器只处理<b>顶层</b>选项：遇到 {@code {} / [] / 引号} 会做深度跳过，
 * 所以 {@code @e[nbt={type:"x"}]} 里的 {@code type} 不会被误认为选择器选项。
 */
public final class SelectorHintsScanner {

    private SelectorHintsScanner() {
    }

    /**
     * @param reader        {@code EntitySelectorParser#reader}
     * @param startPosition {@code EntitySelectorParser#startPosition}（选择器起始游标）
     * @return 可用的索引提示；{@code null} 表示无法优化（不是选择器、没有可用键、或解析异常）
     */
    public static @Nullable SelectorHints scan(final @Nullable StringReader reader, final int startPosition) {
        if (reader == null) {
            return null;
        }
        try {
            final String input = reader.getString();
            final int start = Math.max(0, Math.min(startPosition, input.length()));
            final int end = Math.max(start, Math.min(reader.getCursor(), input.length()));
            return scan(input.substring(start, end));
        } catch (final Throwable t) {
            return null; // 任何异常都视为"不优化"，绝不影响命令执行
        }
    }

    /**
     * 扫描一段完整的选择器文本，例如 {@code @e[type=marker,tag=a,tag=!b,distance=..10]}。
     */
    public static @Nullable SelectorHints scan(final @Nullable String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '@') {
            return null;
        }
        final int bracket = text.indexOf('[');
        if (bracket < 0) {
            return null; // @e / @a 等无选项形式，没有可索引信息
        }

        final int close = matchingBracket(text, bracket);
        if (close < 0) {
            return null;
        }

        final SelectorHints hints = new SelectorHints();
        for (final String option : splitTopLevel(text.substring(bracket + 1, close))) {
            final int eq = option.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = option.substring(0, eq).trim();
            String value = option.substring(eq + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            boolean inverted = false;
            if (value.charAt(0) == '!') {
                inverted = true;
                value = value.substring(1);
            }

            if (key.equals("tag")) {
                if (!value.isEmpty()) {
                    hints.addTag(value, inverted);
                }
            } else if (key.equals("type")) {
                if (value.isEmpty()) {
                    continue;
                }
                if (value.charAt(0) == '#') {
                    hints.setTypeTag(value.substring(1), inverted);
                } else {
                    final EntityType<?> type = lookupType(value);
                    if (type == null) {
                        return null; // 无法识别的类型：放弃优化，让原版去报错
                    }
                    hints.setType(type, inverted);
                }
            }
        }

        return hints.hasIndexKey() ? hints : null;
    }

    private static @Nullable EntityType<?> lookupType(final String id) {
        try {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(null);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 从 {@code text[open] == '['} 开始找匹配的 {@code ']'}，跳过引号内容。返回 -1 表示不匹配。 */
    private static int matchingBracket(final String text, final int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return c == ']' ? i : -1;
                }
            }
        }
        return -1;
    }

    /** 按顶层 {@code ','} 切分：跳过 {@code {} / []} 内部与引号内部。 */
    private static List<String> splitTopLevel(final String body) {
        final List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int mark = 0;
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(mark, i));
                mark = i + 1;
            }
        }
        if (mark <= body.length()) {
            out.add(body.substring(mark));
        }
        return out;
    }
}
