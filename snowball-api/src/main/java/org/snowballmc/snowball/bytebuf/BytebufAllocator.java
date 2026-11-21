package org.snowballmc.snowball.bytebuf;

import org.jetbrains.annotations.NotNull;

public interface BytebufAllocator {

    @NotNull Bytebuf newBytebuf(int size);

    @NotNull Bytebuf toBytebuf(@NotNull byte[] bytes);
}
