package org.snowballmc.snowball.protocol.bladeren;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.protocol.core.ProtocolHandler;
import org.snowballmc.snowball.protocol.core.invoker.PlayerInvokerHolder;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Suite
@SelectClasses(MsptSyncProtocolTest.class)
public class MsptSyncProtocolTestSuite {
}

class MsptSyncProtocolTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void repeatedSubscriptionsAndUnsubscriptionsSendAtMostOnePacket() {
        try (var fixture = new Fixture()) {
            fixture.setSubscribed(true);
            fixture.assertPacket(10.0, 20.0);
            fixture.setSubscribed(true);
            fixture.assertPacket(10.0, 20.0);
            fixture.setSubscribed(false);
            fixture.setSubscribed(false);
            fixture.assertNoPacket();
        }
    }

    @Test
    void logoutCleansUpWhileDisabledAndQueuedSubscribeCannotReviveTheSession() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            fixture.setSubscribed(true);
            fixture.assertPacket(10.0, 20.0);

            Queue<Runnable> mainThreadTasks = new ArrayDeque<>();
            doAnswer(call -> {
                mainThreadTasks.add(call.getArgument(0));
                return null;
            }).when(fixture.server).scheduleOnMain(any(Runnable.class));
            when(fixture.server.isSameThread()).thenReturn(false);
            fixture.setSubscribed(true);
            when(fixture.server.isSameThread()).thenReturn(true);
            assertEquals(1, mainThreadTasks.size());

            LeavesConfig.protocol.bladeren.msptSyncProtocol = false;
            var leave = MsptSyncProtocol.class.getMethod("onPlayerLoggedOut", ServerPlayer.class);
            new PlayerInvokerHolder<>(new MsptSyncProtocol(), leave, leave.getAnnotation(ProtocolHandler.PlayerLeave.class)).invoke(fixture.player);
            LeavesConfig.protocol.bladeren.msptSyncProtocol = true;
            fixture.assertNoPacket();

            when(fixture.playerList.getPlayer(fixture.uuid)).thenReturn(null);
            mainThreadTasks.remove().run();
            fixture.assertNoPacket();
        }
    }

    @Test
    void packetUsesConfiguredRateAndKeepsMsptBeforeTps() {
        try (var fixture = new Fixture()) {
            fixture.setSubscribed(true);
            for (int rate : new int[] {10, 20, 40}) {
                fixture.tickManager.setTickRate(rate);
                fixture.assertPacket(10.0, rate);
            }
            when(fixture.server.getAverageTickTimeNanos()).thenReturn(100_000_000L);
            fixture.assertPacket(100.0, 10.0);
            when(fixture.server.getAverageTickTimeNanos()).thenReturn(-1L);
            fixture.assertNoPacket();
        }
    }

    @Test
    void rejectsInvalidSamplesAndHandlesFreezeAndSprint() {
        assertEquals(new MsptSyncProtocol.TickSample(10.0, 0.0), MsptSyncProtocol.sample(10.0, 50.0, true, false));
        assertEquals(new MsptSyncProtocol.TickSample(10.0, 100.0), MsptSyncProtocol.sample(10.0, 50.0, false, true));
        assertEquals(new MsptSyncProtocol.TickSample(0.0, 20.0), MsptSyncProtocol.sample(0.0, 50.0, false, false));
        assertNull(MsptSyncProtocol.sample(0.0, 50.0, false, true));
        assertNull(MsptSyncProtocol.sample(-1.0, 50.0, false, false));
        assertNull(MsptSyncProtocol.sample(Double.NaN, 50.0, false, false));
        assertNull(MsptSyncProtocol.sample(10.0, Double.POSITIVE_INFINITY, false, false));
        assertNull(MsptSyncProtocol.sample(10.0, 0.0, false, false));
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerList playerList = mock(PlayerList.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class);
        final ServerTickRateManager tickManager = new ServerTickRateManager(server);
        final UUID uuid = UUID.randomUUID();
        final org.mockito.MockedStatic<MinecraftServer> serverStatic = mockStatic(MinecraftServer.class);
        final boolean originalBladerenEnabled = LeavesConfig.protocol.bladeren.enable;
        final boolean originalSyncEnabled = LeavesConfig.protocol.bladeren.msptSyncProtocol;

        Fixture() {
            LeavesConfig.protocol.bladeren.enable = true;
            LeavesConfig.protocol.bladeren.msptSyncProtocol = true;
            serverStatic.when(MinecraftServer::getServer).thenReturn(server);
            when(server.isSameThread()).thenReturn(true);
            when(server.getPlayerList()).thenReturn(playerList);
            when(server.getAverageTickTimeNanos()).thenReturn(10_000_000L);
            when(server.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(server.tickRateManager()).thenReturn(tickManager);
            when(player.getUUID()).thenReturn(uuid);
            when(playerList.getPlayer(uuid)).thenReturn(player);
            when(connection.isAcceptingMessages()).thenReturn(true);
            player.connection = connection;
        }

        void setSubscribed(boolean value) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Value", Boolean.toString(value));
            MsptSyncProtocol.handleSubscription(player, tag);
        }

        void assertPacket(double mspt, double tps) {
            var sent = org.mockito.ArgumentCaptor.forClass(ClientboundCustomPayloadPacket.class);
            MsptSyncProtocol.tick();
            verify(connection).send(sent.capture());
            var payload = assertInstanceOf(DiscardedPayload.class, sent.getValue().payload());
            assertEquals(MsptSyncProtocol.id("mspt_sync"), payload.id());
            ByteBuffer buf = ByteBuffer.wrap(payload.data());
            assertEquals(2 * Double.BYTES, buf.remaining());
            assertEquals(mspt, buf.getDouble());
            assertEquals(tps, buf.getDouble());
            clearInvocations(connection);
        }

        void assertNoPacket() {
            MsptSyncProtocol.tick();
            verify(connection, never()).send(any(ClientboundCustomPayloadPacket.class));
        }

        @Override
        public void close() {
            when(server.isSameThread()).thenReturn(true);
            MsptSyncProtocol.onPlayerLoggedOut(player);
            LeavesConfig.protocol.bladeren.enable = originalBladerenEnabled;
            LeavesConfig.protocol.bladeren.msptSyncProtocol = originalSyncEnabled;
            serverStatic.close();
        }
    }
}
