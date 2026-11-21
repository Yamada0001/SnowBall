package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to mount to nearby vehicles.
 */
public interface MountAction extends BotAction<MountAction> {
    static @NotNull MountAction create() {
        return Bukkit.getBotManager().newAction(MountAction.class);
    }
}
