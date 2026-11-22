package org.snowballmc.snowball.bot.agent.actions;

import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.bot.agent.ExtraData;
import org.snowballmc.snowball.command.CommandContext;
import org.snowballmc.snowball.command.arguments.EnumArgumentType;
import org.snowballmc.snowball.entity.bot.action.MoveAction.MoveDirection;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftMoveAction;

public class ServerMoveAction extends AbstractStateBotAction<ServerMoveAction> {
    private MoveDirection direction = MoveDirection.FORWARD;

    public ServerMoveAction() {
        super("move");
        this.addArgument("direction", EnumArgumentType.fromEnum(MoveDirection.class));
    }

    @Override
    public void loadCommand(@NotNull CommandContext context) {
        this.direction = context.getArgument("direction", MoveDirection.class);
    }

    @Override
    protected void finishStop(@NotNull ServerBot bot) {
        super.finishStop(bot);
        switch (direction) {
            case FORWARD, BACKWARD -> bot.zza = 0.0f;
            case LEFT, RIGHT -> bot.xxa = 0.0f;
        }
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        boolean isSneaking = bot.isShiftKeyDown();
        float velocity = isSneaking ? 0.3f : 1.0f;
        switch (direction) {
            case FORWARD -> bot.zza = velocity;
            case BACKWARD -> bot.zza = -velocity;
            case LEFT -> bot.xxa = velocity;
            case RIGHT -> bot.xxa = -velocity;
        }
        return true;
    }

    @Override
    public String getActionDataString(@NotNull ExtraData data) {
        data.add("direction", direction.name);
        return super.getActionDataString(data);
    }

    public MoveDirection getDirection() {
        return direction;
    }

    public void setDirection(MoveDirection direction) {
        this.direction = direction;
    }

    @Override
    public CraftBotAction<?, ServerMoveAction> asCraft() {
        return new CraftMoveAction(this);
    }
}