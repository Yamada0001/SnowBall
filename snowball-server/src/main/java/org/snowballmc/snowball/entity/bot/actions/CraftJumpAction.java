package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerJumpAction;
import org.snowballmc.snowball.entity.bot.action.JumpAction;

public class CraftJumpAction extends CraftTimerBotAction<JumpAction, ServerJumpAction> implements JumpAction {

    public CraftJumpAction(ServerJumpAction serverAction) {
        super(serverAction, CraftJumpAction::new);
    }
}
