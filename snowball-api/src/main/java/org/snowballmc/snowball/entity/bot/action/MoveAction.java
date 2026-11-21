package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to move to a specific direction.
 */
public interface MoveAction extends StateBotAction<MoveAction> {

    /**
     * Gets the direction of the move action.
     *
     * @return the direction of the move action
     */
    @NotNull MoveDirection getDirection();

    /**
     * Sets the direction of the move action.
     *
     * @param direction the direction to set
     * @return this action instance
     */
    @NotNull MoveAction setDirection(@NotNull MoveDirection direction);

    /**
     * Represents possible movement directions for the bot.
     */
    enum MoveDirection {
        FORWARD("forward"),
        BACKWARD("backward"),
        LEFT("left"),
        RIGHT("right");

        public final String name;

        MoveDirection(String name) {
            this.name = name;
        }
    }

    static @NotNull MoveAction create() {
        return Bukkit.getBotManager().newAction(MoveAction.class);
    }
}
