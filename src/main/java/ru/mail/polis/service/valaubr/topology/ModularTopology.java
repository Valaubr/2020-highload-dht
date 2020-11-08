package ru.mail.polis.service.valaubr.topology;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ModularTopology implements Topology<String> {
    @NotNull
    private final List<String> nodes;
    @NotNull
    private final String me;

    /**
     * topology of servise ant methods to work for him.
     *
     * @param nodes - node list
     * @param me    - current server
     */
    public ModularTopology(@NotNull final Set<String> nodes, @NotNull final String me) {
        assert nodes.contains(me);
        this.nodes = new ArrayList<>();
        this.nodes.addAll(nodes);
        this.nodes.sort(String::compareToIgnoreCase);
        this.me = me;
    }

    @NotNull
    @Override
    public String primaryFor(@NotNull final ByteBuffer key) {
        return nodes.get((Math.abs(key.hashCode()) & Integer.MAX_VALUE) % nodes.size());
    }

    @Override
    public boolean isMe(@NotNull final String node) {
        return node.equals(me);
    }

    @NotNull
    @Override
    public List<String> all() {
        return nodes;
    }

    @NotNull
    @Override
    public List<String> ackServers(int ack, ByteBuffer key) {
        ArrayList<String> output = new ArrayList<>();
        for (int i = 0; i < ack; i++) {
            output.add(nodes.get(i));
        }
        return output;
    }

    @Override
    public int size() {
        return nodes.size();
    }
}
