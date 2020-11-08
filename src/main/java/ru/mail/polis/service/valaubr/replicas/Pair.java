package ru.mail.polis.service.valaubr.replicas;

public class AckFromPair {
    private final int ack;
    private final int from;

    public AckFromPair(int ack, int from) {
        this.ack = ack;
        this.from = from;
    }

    public static AckFromPair setAckFrom(String replicas, int size) {
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
            return new AckFromPair(tmpAck, tmpFrom);
        } else {
            String[] values = replicas.split("/");
            tmpAck = Integer.parseInt(values[0]);
            tmpFrom = Integer.parseInt(values[1]);
            if (Integer.parseInt(values[0]) <= Integer.parseInt(values[1])) {
                return new AckFromPair(tmpAck, tmpFrom);
            } else {
                throw new IllegalArgumentException("Incorrect replica parameter");
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
