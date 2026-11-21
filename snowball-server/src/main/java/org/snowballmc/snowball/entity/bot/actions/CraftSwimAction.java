package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerSwimAction;
import org.snowballmc.snowball.entity.bot.action.SwimAction;

public class CraftSwimAction extends CraftBotAction<SwimAction, ServerSwimAction> implements SwimAction {

    public CraftSwimAction(ServerSwimAction serverAction) {
        super(serverAction, CraftSwimAction::new);
    }
}
