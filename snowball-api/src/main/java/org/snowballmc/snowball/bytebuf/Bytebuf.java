package org.snowballmc.snowball.bytebuf;

import com.google.gson.JsonElement;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public interface Bytebuf {

    static @NotNull Bytebuf buf(int size) {
        return Bukkit.getBytebufManager().newBytebuf(size);
    }

    static @NotNull Bytebuf buf() {
        return buf(128);
    }

    static @NotNull Bytebuf of(@NotNull byte[] bytes) {
        return Bukkit.getBytebufManager().toBytebuf(bytes);
    }

    @NotNull byte[] toArray();

    @NotNull Bytebuf clear();

    @NotNull Bytebuf skipBytes(int i);

    int readerIndex();

    @NotNull Bytebuf readerIndex(int i);

    int writerIndex();

    @NotNull Bytebuf writerIndex(int i);

    @NotNull Bytebuf resetReaderIndex();

    @NotNull Bytebuf resetWriterIndex();

    @NotNull Bytebuf writeByte(int i);

    byte readByte();

    @NotNull Bytebuf writeBoolean(boolean b);

    boolean readBoolean();

    @NotNull Bytebuf writeFloat(float f);

    float readFloat();

    @NotNull Bytebuf writeDouble(double d);

    double readDouble();

    @NotNull Bytebuf writeShort(int i);

    short readShort();

    @NotNull Bytebuf writeInt(int i);

    int readInt();

    @NotNull Bytebuf writeLong(long i);

    long readLong();

    @NotNull Bytebuf writeVarInt(int i);

    int readVarInt();

    @NotNull Bytebuf writeVarLong(long i);

    long readVarLong();

    @NotNull Bytebuf writeUUID(@NotNull UUID uuid);

    @NotNull UUID readUUID();

    @NotNull Bytebuf writeEnum(@NotNull Enum<?> instance);

    <T extends Enum<T>> @NotNull T readEnum(@NotNull Class<T> enumClass);

    @NotNull Bytebuf writeUTFString(@NotNull String utf);

    @NotNull String readUTFString();

    @NotNull Bytebuf writeComponentPlain(@NotNull String str);

    @NotNull String readComponentPlain();

    @NotNull Bytebuf writeComponentJson(@NotNull JsonElement json);

    @NotNull JsonElement readComponentJson();

    @NotNull Bytebuf writeItemStack(@NotNull ItemStack itemStack);

    @NotNull ItemStack readItemStack();

    @NotNull Bytebuf writeItemStackList(@NotNull List<ItemStack> itemStacks);

    @NotNull List<ItemStack> readItemStackList();

    @NotNull Bytebuf copy();

    void retain();

    boolean release();
}
