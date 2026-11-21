package org.snowballmc.snowball.event.bot;

import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.entity.bot.Bot;

import java.util.UUID;

public abstract class BotActionEvent extends BotEvent {

    private final String actionName;
    private final UUID actionUUID;

    public BotActionEvent(@NotNull Bot who, @NotNull String actionName, @NotNull UUID actionUUID) {
        super(who);
        this.actionName = actionName;
        this.actionUUID = actionUUID;
    }

    @NotNull
    public String getActionName() {
        return actionName;
    }

    public @NotNull UUID getActionUUID() {
        return actionUUID;
    }
}
