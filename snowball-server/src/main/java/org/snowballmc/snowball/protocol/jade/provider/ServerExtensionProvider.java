package org.snowballmc.snowball.protocol.jade.provider;

import org.snowballmc.snowball.protocol.jade.accessor.Accessor;
import org.snowballmc.snowball.protocol.jade.util.ViewGroup;

import java.util.List;

public interface ServerExtensionProvider<T> extends JadeProvider {
    List<ViewGroup<T>> getGroups(Accessor<?> request);
}