package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to swap items between the main hand and off-hand.
 */
public interface SwapAction extends BotAction<SwapAction> {
    static @NotNull SwapAction create() {
        return Bukkit.getBotManager().newAction(SwapAction.class);
    }
}
