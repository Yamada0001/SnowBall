package org.snowballmc.snowball;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junitpioneer.jupiter.DefaultLocale;
import org.snowballmc.snowball.bot.agent.actions.ServerRotationAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Suite
@SelectClasses(BotRotationSuggestionsTest.class)
public class BotRotationSuggestionsTestSuite {
}

class BotRotationSuggestionsTest {
    @Test
    @DefaultLocale("de-DE")
    void suggestedAnglesAreValidBrigadierArguments() throws Exception {
        Entity sender = mock(Entity.class);
        when(sender.getYaw()).thenReturn(45.5F);
        when(sender.getPitch()).thenReturn(-12.25F);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);

        var arguments = new ServerRotationAction().getArguments().get(0);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("rotation")
            .then(arguments.get(0).getRight().compile().then(arguments.get(1).getRight().compile().executes(context -> {
                assertEquals(45.5F, FloatArgumentType.getFloat(context, "yaw"));
                assertEquals(-12.25F, FloatArgumentType.getFloat(context, "pitch"));
                return Command.SINGLE_SUCCESS;
            }))));

        String input = "rotation ";
        var yaw = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join().getList();
        assertEquals(List.of("45.50"), yaw.stream().map(Suggestion::getText).toList());
        input = yaw.getFirst().apply(input) + " ";
        var pitch = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join().getList();
        assertEquals(List.of("-12.25"), pitch.stream().map(Suggestion::getText).toList());
        assertEquals(Command.SINGLE_SUCCESS, dispatcher.execute(pitch.getFirst().apply(input), source));
    }
}
