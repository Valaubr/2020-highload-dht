package ru.mail.polis.service.valaubr.topology;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.List;

public interface Topology<N> {
    @NotNull
    N primaryFor(@NotNull ByteBuffer key);

    boolean isMe(@NotNull final String node);

    @NotNull
    List<String> all();

    @NotNull
    List<String> ackServers(int from, ByteBuffer key);

    int size();
}
