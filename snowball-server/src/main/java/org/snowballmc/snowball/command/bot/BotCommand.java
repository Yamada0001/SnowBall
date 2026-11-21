package org.snowballmc.snowball.command.bot;

import com.mojang.brigadier.builder.ArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.command.RootNode;
import org.snowballmc.snowball.command.bot.subcommands.ActionCommand;
import org.snowballmc.snowball.command.bot.subcommands.ConfigCommand;
import org.snowballmc.snowball.command.bot.subcommands.CreateCommand;
import org.snowballmc.snowball.command.bot.subcommands.InventoryCommand;
import org.snowballmc.snowball.command.bot.subcommands.ListCommand;
import org.snowballmc.snowball.command.bot.subcommands.LoadCommand;
import org.snowballmc.snowball.command.bot.subcommands.RemoveCommand;
import org.snowballmc.snowball.command.bot.subcommands.SaveCommand;

import static org.snowballmc.snowball.command.CommandUtils.registerPermissions;

public class BotCommand extends RootNode {
    public static final BotCommand INSTANCE = new BotCommand();
    private static final String PERM_BASE = "bukkit.command.bot";

    private BotCommand() {
        super("bot", PERM_BASE);
        this.children(
            ListCommand::new,
            ConfigCommand::new,
            RemoveCommand::new,
            LoadCommand::new,
            SaveCommand::new,
            ActionCommand::new,
            CreateCommand::new,
            InventoryCommand::new
        );
    }

    @Override
    protected ArgumentBuilder<CommandSourceStack, ?> compile() {
        registerPermissions(PERM_BASE, this.children);
        return super.compile();
    }

    public static boolean hasPermission(@NotNull CommandSender sender) {
        return sender.hasPermission(PERM_BASE);
    }

    public static boolean hasPermission(@NotNull CommandSender sender, String subcommand) {
        return sender.hasPermission(PERM_BASE) || sender.hasPermission(PERM_BASE + "." + subcommand);
    }
}
