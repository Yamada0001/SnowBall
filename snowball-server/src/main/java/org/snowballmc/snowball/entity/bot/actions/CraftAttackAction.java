package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerAttackAction;
import org.snowballmc.snowball.entity.bot.action.AttackAction;

public class CraftAttackAction extends CraftTimerBotAction<AttackAction, ServerAttackAction> implements AttackAction {

    public CraftAttackAction(ServerAttackAction serverAction) {
        super(serverAction, CraftAttackAction::new);
    }
}
