package org.snowballmc.snowball.entity.bot.actions;

import org.snowballmc.snowball.bot.agent.actions.ServerMountAction;
import org.snowballmc.snowball.entity.bot.action.MountAction;

public class CraftMountAction extends CraftBotAction<MountAction, ServerMountAction> implements MountAction {

    public CraftMountAction(ServerMountAction serverAction) {
        super(serverAction, CraftMountAction::new);
    }
}
