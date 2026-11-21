package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerSneakAction;
import org.snowballmc.snowball.entity.bot.action.SneakAction;

public class CraftSneakAction extends CraftBotAction<SneakAction, ServerSneakAction> implements SneakAction {

    public CraftSneakAction(ServerSneakAction serverAction) {
        super(serverAction, CraftSneakAction::new);
    }
}
