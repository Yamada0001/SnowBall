package org.snowballmc.snowball.experimental.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.BossEvent;
import org.jspecify.annotations.Nullable;
import org.snowballmc.snowball.experimental.ExperimentalConfig;

/** A session-only boss bar. All viewer operations run on the server coordinator. */
public final class WorldTpsBar {
    private static final SimpleCommandExceptionType NOT_CONNECTED = new SimpleCommandExceptionType(Component.literal("Only connected players can use /tpsbar."));

    private WorldTpsBar() {}

    public static boolean enabled() {
        return enabled(ExperimentalConfig.current().world());
    }

    static boolean enabled(final ExperimentalConfig.World config) {
        // This is independent of worker admission (for example, any online player disables worker ticks).
        return config.enabled() && config.usePerWorldTpsBar();
    }

    public static boolean samplesWorlds() {
        return samplesWorlds(ExperimentalConfig.current().world());
    }

    static boolean samplesWorlds(final ExperimentalConfig.World config) {
        return enabled(config) && !config.showTpsOfServerInsteadOfWorld();
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpsbar")
            .requires(source -> enabled() && source.isPlayer())
            .executes(context -> toggle(context.getSource())));
    }

    private static int toggle(final CommandSourceStack source) throws CommandSyntaxException {
        final MinecraftServer server = source.getServer();
        final ServerPlayer player = source.getPlayerOrException();
        if (Thread.currentThread() != server.getRunningThread() || player.hasDisconnected()
            || !server.getPlayerList().realPlayers.contains(player) || !player.connection.isAcceptingMessages()) {
            throw NOT_CONNECTED.create();
        }
        final ServerGamePacketListenerImpl connection = player.connection;
        final ServerBossEvent current = connection.snowball$tpsBar;
        if (current != null) {
            connection.snowball$tpsBar = null;
            current.removePlayer(player);
        } else {
            final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
            final ServerLevel world = ExperimentalConfig.current().world().showTpsOfServerInsteadOfWorld() ? null : player.level();
            display(world, null, server.tickRateManager().tickrate()).apply(bar);
            bar.addPlayer(player);
            connection.snowball$tpsBar = bar;
        }
        return 1;
    }

    // Called on the coordinator after tickChildren (including the world-tick barrier) and Paper's
    // recording of this server tick. No viewer inspects a world while its worker may be running.
    public static void refresh(final MinecraftServer server, final long now) {
        final boolean serverMode = ExperimentalConfig.current().world().showTpsOfServerInsteadOfWorld();
        final long interval = server.tickRateManager().nanosecondsPerTick();
        final float target = server.tickRateManager().tickrate();
        IdentityHashMap<ServerLevel, Display> displays = null;
        for (final ServerPlayer player : server.getPlayerList().realPlayers) {
            final ServerGamePacketListenerImpl connection = player.connection;
            final ServerBossEvent bar = connection.snowball$tpsBar;
            if (bar == null || !connection.isAcceptingMessages()) {
                continue;
            }
            if (displays == null) {
                displays = new IdentityHashMap<>();
            }
            // Use the player's current level, not the command execution position or the world at opt-in.
            final ServerLevel world = serverMode ? null : player.level();
            Display display = displays.get(world);
            if (display == null) {
                final Double tps = world == null ? server.tickTimes5s.getTPSAverage(null, interval) : world.snowball$getWorldTickPacing(now, interval);
                display = display(world, tps, target);
                displays.put(world, display);
            }
            display.apply(bar);
        }
    }

    private static Display display(final @Nullable ServerLevel world, final @Nullable Double tps, final float target) {
        final boolean measured = tps != null && Double.isFinite(tps) && tps > 0.0;
        final String scope = world == null ? "Server" : world.dimension().identifier().toString();
        final String value = measured ? String.format(Locale.ROOT, "%.1f", tps) : "N/A";
        return new Display(Component.literal(scope + " TPS (5s tick pacing): " + value), measured ? (float)Math.min(tps / target, 1.0) : 0.0F);
    }

    private record Display(Component title, float progress) {
        void apply(final ServerBossEvent bar) {
            bar.setName(this.title);
            bar.setProgress(this.progress);
        }
    }
}
