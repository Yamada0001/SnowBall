package org.snowballmc.snowball.event.bot;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.entity.bot.Bot;

import java.util.UUID;

public class BotActionScheduleEvent extends BotActionEvent implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final CommandSender sender;
    private boolean cancel = false;

    public BotActionScheduleEvent(@NotNull Bot who, @NotNull String actionName, @NotNull UUID actionUUID, @Nullable CommandSender sender) {
        super(who, actionName, actionUUID);
        this.sender = sender;
    }

    public static @NotNull HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return cancel;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    @Nullable
    public CommandSender getSender() {
        return sender;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return handlers;
    }
}
