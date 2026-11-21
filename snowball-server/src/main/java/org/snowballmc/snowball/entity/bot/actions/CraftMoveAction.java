package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerMoveAction;
import org.snowballmc.snowball.entity.bot.action.MoveAction;

public class CraftMoveAction extends CraftBotAction<MoveAction, ServerMoveAction> implements MoveAction {

    public CraftMoveAction(ServerMoveAction serverAction) {
        super(serverAction, CraftMoveAction::new);
    }

    @Override
    public MoveDirection getDirection() {
        return serverAction.getDirection();
    }

    @Override
    public MoveAction setDirection(MoveDirection direction) {
        serverAction.setDirection(direction);
        return this;
    }
}
