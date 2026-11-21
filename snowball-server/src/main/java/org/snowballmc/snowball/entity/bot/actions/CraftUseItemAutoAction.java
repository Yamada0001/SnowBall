package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerUseItemAutoAction;
import org.snowballmc.snowball.entity.bot.action.UseItemAutoAction;

public class CraftUseItemAutoAction extends CraftTimerBotAction<UseItemAutoAction, ServerUseItemAutoAction> implements UseItemAutoAction {

    public CraftUseItemAutoAction(ServerUseItemAutoAction serverAction) {
        super(serverAction, CraftUseItemAutoAction::new);
    }

    @Override
    public int getUseTickTimeout() {
        return serverAction.getUseTickTimeout();
    }

    @Override
    public CraftUseItemAutoAction setUseTickTimeout(int timeout) {
        serverAction.setUseTickTimeout(timeout);
        return this;
    }
}
