package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerSwapAction;
import org.snowballmc.snowball.entity.bot.action.SwapAction;

public class CraftSwapAction extends CraftBotAction<SwapAction, ServerSwapAction> implements SwapAction {

    public CraftSwapAction(ServerSwapAction serverAction) {
        super(serverAction, CraftSwapAction::new);
    }
}
