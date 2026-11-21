package org.snowballmc.snowball.command.bot.subcommands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.bot.BotList;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.command.ArgumentNode;
import org.snowballmc.snowball.command.CommandContext;
import org.snowballmc.snowball.command.bot.BotSubcommand;

import java.util.concurrent.CompletableFuture;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /bot openinv <bot_name>} —— 直接打开指定假人的背包界面，不必走到假人面前空手右键。
 *
 * <p>实际打开逻辑复用 {@link ServerBot#openInventory(ServerPlayer)}，因此
 * {@code BotInventoryOpenEvent} 照常发出，插件仍可拦截。</p>
 */
public class InventoryCommand extends BotSubcommand {

    public InventoryCommand() {
        super("openinv");
        children(BotNameArgument::new);
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return LeavesConfig.modify.fakeplayer.canOpenInventory && super.requires(source);
    }

    private static class BotNameArgument extends ArgumentNode<String> {

        private BotNameArgument() {
            super("bot_name", StringArgumentType.word());
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            CommandSender sender = context.getSender();
            if (!(sender instanceof Player player)) {
                sender.sendMessage(text("This command can only be run by a player", NamedTextColor.RED));
                return false;
            }

            String botName = context.getArgument(BotNameArgument.class);
            ServerBot bot = BotList.INSTANCE.getBotByName(botName);
            if (bot == null) {
                sender.sendMessage(text("Fakeplayer " + botName + " does not exist", NamedTextColor.RED));
                return false;
            }

            bot.openInventory(((CraftPlayer) player).getHandle());
            return true;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(CommandContext context, @NotNull SuggestionsBuilder builder) {
            for (ServerBot bot : BotList.INSTANCE.bots) {
                builder.suggest(bot.getBukkitEntity().getName());
            }
            return builder.buildFuture();
        }
    }
}
