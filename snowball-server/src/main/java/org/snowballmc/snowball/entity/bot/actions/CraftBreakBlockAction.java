package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerBreakBlockAction;
import org.snowballmc.snowball.entity.bot.action.BreakBlockAction;

public class CraftBreakBlockAction extends CraftTimerBotAction<BreakBlockAction, ServerBreakBlockAction> implements BreakBlockAction {

    public CraftBreakBlockAction(ServerBreakBlockAction serverAction) {
        super(serverAction, CraftBreakBlockAction::new);
    }
}
