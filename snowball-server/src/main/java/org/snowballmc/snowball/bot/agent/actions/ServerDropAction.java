package org.snowballmc.snowball.bot.agent.actions;

import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftDropAction;

public class ServerDropAction extends AbstractTimerBotAction<ServerDropAction> {

    public ServerDropAction() {
        super("drop");
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        bot.dropAll(false);
        return true;
    }

    @Override
    public CraftBotAction<?, ServerDropAction> asCraft() {
        return new CraftDropAction(this);
    }
}
