package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerUseItemOffhandAction;
import org.snowballmc.snowball.entity.bot.action.UseItemOffhandAction;

public class CraftUseItemOffhandAction extends CraftTimerBotAction<UseItemOffhandAction, ServerUseItemOffhandAction> implements UseItemOffhandAction {

    public CraftUseItemOffhandAction(ServerUseItemOffhandAction serverAction) {
        super(serverAction, CraftUseItemOffhandAction::new);
    }

    @Override
    public int getUseTickTimeout() {
        return serverAction.getUseTickTimeout();
    }

    @Override
    public CraftUseItemOffhandAction setUseTickTimeout(int timeout) {
        serverAction.setUseTickTimeout(timeout);
        return this;
    }
}
