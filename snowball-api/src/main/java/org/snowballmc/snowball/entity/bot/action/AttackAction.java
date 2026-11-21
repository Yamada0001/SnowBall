package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to attack entities.
 */
public interface AttackAction extends TimerBotAction<AttackAction> {
    static @NotNull AttackAction create() {
        return Bukkit.getBotManager().newAction(AttackAction.class);
    }
}
