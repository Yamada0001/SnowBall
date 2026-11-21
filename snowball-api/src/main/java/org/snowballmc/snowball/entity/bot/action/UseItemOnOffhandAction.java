package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action where a bot uses an item in its offhand on a block.
 */
public interface UseItemOnOffhandAction extends TimerBotAction<UseItemOnOffhandAction> {

    /**
     * Gets the equivalent right-click hold duration timeout in ticks.
     * Default is -1, which means no timeout.
     *
     * @return the equivalent right-click hold duration timeout
     */
    int getUseTickTimeout();

    /**
     * Sets the equivalent right-click hold duration timeout in ticks.
     *
     * @param timeout the equivalent right-click hold duration timeout
     * @return this action instance
     */
    @NotNull UseItemOnOffhandAction setUseTickTimeout(int timeout);

    static @NotNull UseItemOnOffhandAction create() {
        return Bukkit.getBotManager().newAction(UseItemOnOffhandAction.class);
    }
}
