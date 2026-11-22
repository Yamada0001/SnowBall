package org.snowballmc.snowball.bot.agent.actions;

import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftSneakAction;

public class ServerSneakAction extends AbstractStateBotAction<ServerSneakAction> {

    public ServerSneakAction() {
        super("sneak");
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        if (bot.isShiftKeyDown()) {
            return false;
        }

        bot.setShiftKeyDown(true);
        return true;
    }

    @Override
    protected void finishStop(@NotNull ServerBot bot) {
        super.finishStop(bot);
        bot.setShiftKeyDown(false);
    }

    @Override
    public CraftBotAction<?, ServerSneakAction> asCraft() {
        return new CraftSneakAction(this);
    }
}
