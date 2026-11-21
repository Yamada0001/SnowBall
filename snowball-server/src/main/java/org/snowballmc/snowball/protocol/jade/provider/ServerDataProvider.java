package org.snowballmc.snowball.protocol.jade.provider;

import net.minecraft.nbt.CompoundTag;
import org.snowballmc.snowball.protocol.jade.accessor.Accessor;

public interface ServerDataProvider<T extends Accessor<?>> extends JadeProvider {
    void appendServerData(CompoundTag data, T accessor);
}
