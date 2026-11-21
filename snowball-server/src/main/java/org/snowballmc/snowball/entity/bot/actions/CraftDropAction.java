package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerDropAction;
import org.snowballmc.snowball.entity.bot.action.DropAction;

public class CraftDropAction extends CraftTimerBotAction<DropAction, ServerDropAction> implements DropAction {

    public CraftDropAction(ServerDropAction serverAction) {
        super(serverAction, CraftDropAction::new);
    }
}
