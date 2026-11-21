package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to perform a jump.
 */
public interface JumpAction extends TimerBotAction<JumpAction> {
    static @NotNull JumpAction create() {
        return Bukkit.getBotManager().newAction(JumpAction.class);
    }
}
