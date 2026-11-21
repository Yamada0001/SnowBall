package org.snowballmc.snowball.entity.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public interface BotCreator {

    static @NotNull BotCreator of(@NotNull String rawName, @NotNull Location location) {
        return Bukkit.getBotManager().botCreator(rawName, location);
    }

    @NotNull BotCreator name(@NotNull String name);

    @NotNull BotCreator skinName(@Nullable String skinName);

    @NotNull BotCreator skin(@Nullable String[] skin);

    /**
     * Sets the skin of the bot using the Mojang API based on the provided skin name.
     * <p>
     * Need Async.
     *
     * @return BotCreator
     */
    @NotNull BotCreator mojangAPISkin();

    @NotNull BotCreator location(@NotNull Location location);

    @NotNull BotCreator creator(@Nullable CommandSender creator);

    /**
     * Create a bot directly
     *
     * @return a bot, null spawn fail
     */
    @Nullable Bot spawn();

    /**
     * Create a bot and apply skin of player names `skinName` from MojangAPI
     * just like `mojangAPISkin().spawn()`, but async
     * <p>
     * you can not get the bot instance instantly because get skin in on async thread
     *
     * @param consumer Consumer
     */
    void spawnWithSkin(@Nullable Consumer<Bot> consumer);
}
