package ru.mail.polis.service.valaubr.replicas;

import one.nio.http.Response;

import java.util.List;

public class ReplicasResponses {
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final int CREATED = 201;
    private static final int ACCEPTED = 202;
    private static final int NOT_FOUND = 404;
    private static final int OK = 200;

    public static Response get(final List<Response> responses, final Pair ackFrom) {
        Response outputValue = new Response(Response.NOT_FOUND, Response.EMPTY);
        int count = 0;
        long currentTimeStamp = 0L;
        long tmpTimeStamp = 0L;
        for (final Response response : responses) {
            switch (response.getStatus()) {
                case OK:
                    count++;
                    if (response.getHeader("TimeStamp") != null) {
                        tmpTimeStamp = Long.parseLong(response.getHeader("TimeStamp"));
                        if (currentTimeStamp < tmpTimeStamp) {
                            outputValue = response;
                            currentTimeStamp = tmpTimeStamp;
                        }
                    }
                    break;
                case NOT_FOUND:
                    count++;
                    break;
            }
        }
        if (count >= ackFrom.getAck()) {
            if (outputValue.getHeader("TOMBSTONE") != null) return new Response(Response.NOT_FOUND, Response.EMPTY);
            else return outputValue;
        } else {
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
    }

    public static Response put(final List<Response> responses, final Pair ackFrom) {
        int ackCount = (int) responses.stream().filter(response -> response.getStatus() == CREATED).count();
        return ackCount >= ackFrom.getAck() ? new Response(Response.CREATED, Response.EMPTY) : new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
    }

    public static Response delete(final List<Response> responses, final Pair ackFrom) {
        int ackCount = (int) responses.stream().filter(response -> response.getStatus() == ACCEPTED).count();
        return ackCount >= ackFrom.getAck() ? new Response(Response.ACCEPTED, Response.EMPTY) : new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
    }
}
