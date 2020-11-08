package ru.mail.polis.service.valaubr.replicas;

import com.google.common.base.Splitter;

import java.util.List;

public class Pair {
    private final int ack;
    private final int from;

    public Pair(int ack, int from) {
        this.ack = ack;
        this.from = from;
    }

    public static Pair setAckFrom(String replicas, int size) {
        int tmpAck;
        int tmpFrom;
        if (replicas == null) {
            tmpFrom = size;
            if (size > 3) {
                tmpAck = size / 2 + (size / 4);
            } else if (size == 1) {
                tmpAck = 1;
            } else {
                tmpAck = 2;
            }
            return new Pair(tmpAck, tmpFrom);
        } else {
            List<String> values = Splitter.on('/').splitToList(replicas);
            tmpAck = Integer.parseInt(values.get(0));
            tmpFrom = Integer.parseInt(values.get(1));
            if (tmpAck <= tmpFrom && tmpAck > 0) {
                return new Pair(tmpAck, tmpFrom);
            } else {
                return null;
            }
        }
    }

    public int getFrom() {
        return from;
    }

    public int getAck() {
        return ack;
    }
}
