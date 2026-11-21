package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftSwimAction;

public class ServerSwimAction extends AbstractStateBotAction<ServerSwimAction> {

    public ServerSwimAction() {
        super("swim");
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        if (bot.isInWater()) {
            bot.addDeltaMovement(new Vec3(0, 0.03, 0));
        }
        return true;
    }

    @Override
    public CraftBotAction<?, ServerSwimAction> asCraft() {
        return new CraftSwimAction(this);
    }
}
