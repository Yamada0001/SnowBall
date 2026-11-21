package org.snowballmc.snowball.command.leaves;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.command.RootNode;
import org.snowballmc.snowball.command.leaves.subcommands.BlockUpdateCommand;
import org.snowballmc.snowball.command.leaves.subcommands.ConfigCommand;
import org.snowballmc.snowball.command.leaves.subcommands.CounterCommand;
import org.snowballmc.snowball.command.leaves.subcommands.ReloadCommand;
import org.snowballmc.snowball.command.leaves.subcommands.ReportCommand;
import org.snowballmc.snowball.command.leaves.subcommands.UpdateCommand;

public class LeavesCommand extends RootNode {

    public static final LeavesCommand INSTANCE = new LeavesCommand();
    private static final String PERM_BASE = "bukkit.command.leaves";

    private LeavesCommand() {
        super("leaves", PERM_BASE);
        children(
            BlockUpdateCommand::new,
            ConfigCommand::new,
            CounterCommand::new,
            ReloadCommand::new,
            ReportCommand::new,
            UpdateCommand::new
        );
    }

    public static boolean hasPermission(@NotNull CommandSender sender, String subcommand) {
        return sender.hasPermission(PERM_BASE) || sender.hasPermission(PERM_BASE + "." + subcommand);
    }
}
