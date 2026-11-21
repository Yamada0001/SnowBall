package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerFishAction;
import org.snowballmc.snowball.entity.bot.action.FishAction;

public class CraftFishAction extends CraftTimerBotAction<FishAction, ServerFishAction> implements FishAction {

    public CraftFishAction(ServerFishAction serverAction) {
        super(serverAction, CraftFishAction::new);
    }
}
