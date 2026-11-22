package org.snowballmc.snowball.protocol.bladeren;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.protocol.core.LeavesProtocol;
import org.snowballmc.snowball.protocol.core.ProtocolHandler;
import org.snowballmc.snowball.protocol.core.ProtocolUtils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@LeavesProtocol.Register(namespace = "bladeren")
public class MsptSyncProtocol implements LeavesProtocol {

    public static final String PROTOCOL_ID = "bladeren";
    private static final Identifier MSPT_SYNC = id("mspt_sync");
    // Subscriptions belong to a live player session, not an account UUID.
    private static final Set<ServerPlayer> players = Collections.newSetFromMap(new IdentityHashMap<>());

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.tryBuild(PROTOCOL_ID, path);
    }

    @ProtocolHandler.Init
    public static void init() {
        BladerenProtocol.registerFeature("mspt_sync", MsptSyncProtocol::handleSubscription);
    }

    static void handleSubscription(ServerPlayer player, CompoundTag tag) {
        if (tag.getStringOr("Value", "").equals("true")) {
            onPlayerSubmit(player);
        } else {
            setSubscribed(player, false);
        }
    }

    private static void setSubscribed(ServerPlayer player, boolean subscribed) {
        MinecraftServer server = MinecraftServer.getServer();
        // Leaves payload handlers can run on a network thread; serialize changes with tick and logout.
        if (server.isSameThread()) {
            updateSubscription(server, player, subscribed);
        } else if (!server.isStopped()) {
            server.scheduleOnMain(() -> updateSubscription(server, player, subscribed));
        }
    }

    private static void updateSubscription(MinecraftServer server, ServerPlayer player, boolean subscribed) {
        if (!subscribed) {
            players.remove(player);
        } else if (isSyncEnabled() && player.connection != null && player.connection.isAcceptingMessages()
            && server.getPlayerList().getPlayer(player.getUUID()) == player) {
            players.add(player);
        }
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLoggedOut(@NotNull ServerPlayer player) {
        setSubscribed(player, false);
    }

    @ProtocolHandler.Ticker
    public static void tick() {
        if (players.isEmpty() || !isSyncEnabled()) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        ServerTickRateManager tickManager = server.tickRateManager();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        TickSample sample = sample(mspt, tickManager.millisecondsPerTick(), tickManager.isFrozen(), tickManager.isSprinting());
        if (sample != null) {
            players.forEach(player -> ProtocolUtils.sendBytebufPacket(player, MSPT_SYNC, buf -> {
                buf.writeDouble(sample.mspt());
                buf.writeDouble(sample.tps());
            }));
        }
    }

    // Match Servux's estimate: normal ticks are paced, sprint ticks are not, and a freeze reports zero TPS.
    static @Nullable TickSample sample(double mspt, double targetMspt, boolean frozen, boolean sprinting) {
        if (!Double.isFinite(mspt) || mspt < 0.0D) {
            return null;
        }
        if (frozen) {
            return new TickSample(mspt, 0.0D);
        }
        if (!sprinting && (!Double.isFinite(targetMspt) || targetMspt <= 0.0D)) {
            return null;
        }
        double tps = 1_000.0D / (sprinting ? mspt : Math.max(mspt, targetMspt));
        return Double.isFinite(tps) ? new TickSample(mspt, tps) : null;
    }

    public static void onPlayerSubmit(@NotNull ServerPlayer player) {
        setSubscribed(player, true);
    }

    record TickSample(double mspt, double tps) {
    }

    @Override
    public int tickerInterval(String tickerID) {
        return LeavesConfig.protocol.bladeren.msptSyncTickInterval;
    }

    private static boolean isSyncEnabled() {
        var config = LeavesConfig.protocol.bladeren;
        return config.enable && config.msptSyncProtocol;
    }

    @Override
    public boolean isActive() {
        // Keep the leave hook active while sync is disabled so no disconnected sessions are retained.
        return true;
    }
}
