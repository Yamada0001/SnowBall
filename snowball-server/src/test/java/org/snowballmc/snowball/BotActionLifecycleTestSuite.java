package org.snowballmc.snowball;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.mockito.MockedStatic;
import org.snowballmc.snowball.bot.BotList;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.bot.agent.actions.AbstractBotAction;
import org.snowballmc.snowball.bot.agent.actions.AbstractUseBotAction;
import org.snowballmc.snowball.bot.agent.actions.ServerMoveAction;
import org.snowballmc.snowball.command.bot.subcommands.ActionCommand;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.event.bot.BotActionExecuteEvent;
import org.snowballmc.snowball.event.bot.BotActionStopEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Suite
@SelectClasses(BotActionLifecycleTest.class)
public class BotActionLifecycleTestSuite {
}

class BotActionLifecycleTest {
    private final List<BotActionStopEvent> stops = new ArrayList<>();
    private Consumer<Event> listener = event -> {};
    private ServerBot bot;
    private MockedStatic<Bukkit> bukkit;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() {
        bot = mock(ServerBot.class);
        PluginManager manager = mock(PluginManager.class);
        doAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            if (event instanceof BotActionStopEvent stop) {
                stops.add(stop);
            }
            listener.accept(event);
            return null;
        }).when(manager).callEvent(any(Event.class));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
    }

    @AfterEach
    void teardown() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void repeatsAtTheConfiguredIntervalWithoutChangingTheFirstDelay(int interval) {
        RecordingAction action = new RecordingAction();
        action.setStartDelayTick(2);
        action.setDoIntervalTick(interval);
        action.setDoNumber(3);
        action.init();

        int spacing = Math.max(1, interval);
        for (int tick = 0; tick <= 3 + 2 * spacing; tick++) {
            action.tick = tick;
            action.tryTick(bot);
        }

        assertEquals(List.of(2, 2 + spacing, 2 + 2 * spacing), action.executed);
        assertTrue(action.isCancelled());
        assertEquals(1, stops.size());
        assertEquals(BotActionStopEvent.Reason.DONE, stops.getFirst().getReason());
        assertNull(stops.getFirst().getSender());
    }

    @Test
    void executeCancellationRetainsItsCountAndIntervalSemantics() {
        RecordingAction action = new RecordingAction();
        action.setDoIntervalTick(3);
        action.setDoNumber(2);
        action.init();
        List<Integer> attempted = new ArrayList<>();
        listener = event -> {
            if (event instanceof BotActionExecuteEvent execute) {
                attempted.add(action.tick);
                if (action.tick == 0) {
                    execute.setCancelled(true);
                } else if (action.tick == 3) {
                    execute.hardCancel();
                }
            }
        };

        for (int tick = 0; tick <= 7; tick++) {
            action.tick = tick;
            action.tryTick(bot);
        }

        assertEquals(List.of(0, 3, 6), attempted);
        assertEquals(List.of(6), action.executed);
        assertTrue(action.isCancelled());
    }

    @Test
    void savedCountdownResumesAndInitRestartsFromTheFirstDelay() {
        RecordingAction action = new RecordingAction();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(4);
        action.setDoNumber(2);
        action.init();
        for (int tick = 0; tick <= 2; tick++) {
            action.tick = tick;
            action.tryTick(bot);
        }
        assertEquals(List.of(1), action.executed);

        RecordingAction resumed = new RecordingAction();
        resumed.load(action.save(new CompoundTag()));
        assertEquals(action.getUUID(), resumed.getUUID());
        assertEquals(1, resumed.getDoNumberRemaining());
        for (int tick = 3; tick <= 6; tick++) {
            resumed.tick = tick;
            resumed.tryTick(bot);
        }
        assertEquals(List.of(5), resumed.executed);
        assertTrue(resumed.isCancelled());

        resumed.init();
        assertFalse(resumed.isCancelled());
        assertEquals(2, resumed.getDoNumberRemaining());
        resumed.tick = 7;
        resumed.tryTick(bot);
        resumed.tick = 8;
        resumed.tryTick(bot);
        assertEquals(List.of(5, 8), resumed.executed);
    }

    @Test
    void ongoingItemUseKeepsTickingUntilTimeoutThenWaitsForTheInterval() {
        RecordingUseAction action = new RecordingUseAction();
        action.setDoNumber(-1);
        action.setDoIntervalTick(4);
        action.setUseTickTimeout(2);
        when(bot.getUseItemRemainingTicks()).thenReturn(20);
        when(bot.releaseUsingItemWithResult()).thenReturn(true);
        action.init();

        for (int tick = 0; tick <= 6; tick++) {
            action.tick = tick;
            action.tryTick(bot);
        }

        verify(bot).releaseUsingItemWithResult();
        assertEquals(List.of(0, 6), action.started);
        assertEquals(-1, action.getDoNumberRemaining());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "all"})
    void commandStopFiresOneEventPerActionAndHonorsVeto(String target) throws Exception {
        ServerMoveAction accepted = new ServerMoveAction();
        ServerMoveAction vetoed = new ServerMoveAction();
        boolean all = target.equals("all");
        List<AbstractBotAction<?>> actions = new ArrayList<>(all ? List.of(accepted, vetoed) : List.of(vetoed, accepted));
        AtomicInteger callbacks = new AtomicInteger();
        accepted.setOnStop(action -> callbacks.incrementAndGet());
        vetoed.setOnStop(action -> callbacks.incrementAndGet());
        listener = event -> {
            if (event instanceof BotActionStopEvent stop && stop.getActionUUID().equals(vetoed.getUUID())) {
                stop.setCancelled(true);
            }
        };
        when(bot.getBotActions()).thenReturn(actions);
        bot.zza = 1.0F;
        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        when(source.getSender()).thenReturn(sender);
        BotList prior = BotList.INSTANCE;
        BotList bots = mock(BotList.class);
        when(bots.getBotByName("TestBot")).thenReturn(bot);
        BotList.INSTANCE = bots;
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = new TestActionCommand().dispatcher();
            assertEquals(1, dispatcher.execute("action TestBot stop " + target, source));
            if (!all) {
                assertEquals(List.of(vetoed, accepted), actions);
                assertEquals(0, callbacks.get());
                assertEquals(1.0F, bot.zza);
                assertEquals(1, dispatcher.execute("action TestBot stop 1", source));
            }
        } finally {
            BotList.INSTANCE = prior;
        }

        assertEquals(List.of(vetoed), actions);
        assertEquals(1, callbacks.get());
        assertEquals(0.0F, bot.zza);
        assertTrue(accepted.isCancelled());
        assertFalse(vetoed.isCancelled());
        assertEquals(all ? List.of(accepted.getUUID(), vetoed.getUUID()) : List.of(vetoed.getUUID(), accepted.getUUID()),
            stops.stream().map(BotActionStopEvent::getActionUUID).toList());
        for (BotActionStopEvent stop : stops) {
            assertEquals(BotActionStopEvent.Reason.COMMAND, stop.getReason());
            assertSame(sender, stop.getSender());
        }
    }

    @Test
    void legacyPluginStopStillNotifiesAndCannotBeVetoed() {
        ServerMoveAction action = new ServerMoveAction();
        bot.zza = 1.0F;
        listener = event -> ((BotActionStopEvent) event).setCancelled(true);

        action.stop(bot, BotActionStopEvent.Reason.PLUGIN);

        assertTrue(action.isCancelled());
        assertEquals(0.0F, bot.zza);
        assertEquals(1, stops.size());
        assertEquals(BotActionStopEvent.Reason.PLUGIN, stops.getFirst().getReason());
        assertNull(stops.getFirst().getSender());
    }

    private static final class TestActionCommand extends ActionCommand {
        @Override
        public boolean requires(CommandSourceStack source) {
            return true;
        }

        CommandDispatcher<CommandSourceStack> dispatcher() {
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(compile().build());
            return dispatcher;
        }
    }

    private static final class RecordingAction extends AbstractBotAction<RecordingAction> {
        private final List<Integer> executed = new ArrayList<>();
        private int tick;

        RecordingAction() {
            super("recording");
        }

        @Override
        public boolean doTick(ServerBot bot) {
            executed.add(tick);
            return true;
        }

        @Override
        public CraftBotAction<?, RecordingAction> asCraft() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingUseAction extends AbstractUseBotAction<RecordingUseAction> {
        private final List<Integer> started = new ArrayList<>();
        private int tick;

        RecordingUseAction() {
            super("recording_use");
        }

        @Override
        protected boolean interact(ServerBot bot) {
            started.add(tick);
            return true;
        }

        @Override
        public CraftBotAction<?, RecordingUseAction> asCraft() {
            throw new UnsupportedOperationException();
        }
    }
}
