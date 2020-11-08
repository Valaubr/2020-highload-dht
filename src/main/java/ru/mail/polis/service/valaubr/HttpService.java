package ru.mail.polis.service.valaubr;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.valaubr.Cell;
import ru.mail.polis.dao.valaubr.Value;
import ru.mail.polis.service.Service;
import ru.mail.polis.service.valaubr.replicas.Pair;
import ru.mail.polis.service.valaubr.replicas.ReplicasResponses;
import ru.mail.polis.service.valaubr.topology.ModularTopology;
import ru.mail.polis.service.valaubr.topology.Topology;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpService extends HttpServer implements Service {
    private static final String UNIVERSAL_MESSAGE = "Empty id && response is dropped";
    private final DAO dao;
    private final Logger logger = LoggerFactory.getLogger(HttpService.class);
    private final ExecutorService executor;
    private final Map<String, HttpClient> nodeToClient;
    private final Topology<String> topology;

    /**
     * Constructor of the service.
     *
     * @param port            - port of connection
     * @param base            - object of storage
     * @param modularTopology - topology of service
     * @throws IOException - exceptions
     */
    public HttpService(final int port,
                       @NotNull final DAO base,
                       final int threadPool,
                       final int queueSize,
                       @NotNull final ModularTopology modularTopology) throws IOException {
        super(config(port, threadPool));
        dao = base;
        topology = modularTopology;
        nodeToClient = new HashMap<>();

        for (final String node : topology.all()) {
            if (topology.isMe(node)) {
                continue;
            }
            final HttpClient client = new HttpClient(new ConnectionString(node + "?timeout=1000"));
            if (nodeToClient.put(node, client) != null) {
                throw new IllegalStateException("Duplicate node");
            }
        }

        this.executor = new ThreadPoolExecutor(
                threadPool,
                queueSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadFactoryBuilder()
                        .setNameFormat("Worker-%d")
                        .setUncaughtExceptionHandler((thread, e) -> logger.error("error in {} thread", thread, e))
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static HttpServerConfig config(final int port, final int threadPool) {
        final AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.deferAccept = true;
        acceptorConfig.reusePort = true;
        final HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.selectors = threadPool;
        httpServerConfig.maxWorkers = threadPool;
        httpServerConfig.minWorkers = threadPool;
        return httpServerConfig;
    }

    private byte[] converterFromByteBuffer(@NotNull final ByteBuffer byteBuffer) {
        if (byteBuffer.hasRemaining()) {
            final byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            return bytes;
        } else {
            return Response.EMPTY;
        }
    }

    /**
     * Return status of server.
     *
     * @return 200 - ok
     */
    @Path("/v0/status")
    public Response status() {
        return Response.ok(Response.OK);
    }


    /**
     * Insertion entity dao by id.
     *
     * @param id - Entity id
     *           201 - Create entity
     *           400 - Empty id in param
     *           500 - Internal error
     */
    @Path("/v0/entity")
    public void entity(@Param(required = true, value = "id") @NotNull final String id,
                       @NotNull final Request request,
                       @NotNull final HttpSession session,
                       @Param(value = "replicas") @NotNull String replicas) {
        if (checkId(id, session)) return;
        try {
            executor.execute(() -> {
                final ByteBuffer key = ByteBuffer.wrap(id.getBytes(Charsets.UTF_8));
                if (request.getHeader("X-Replicas-Finding") != null) {
                    findReplicasParam(request, session, key);
                } else {
                    connectToAllReplicas(session, request, replicas, key);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("Server can`t sending response", e);
        }
    }

    private void findReplicasParam(@NotNull final Request request,
                                   @NotNull final HttpSession session,
                                   @NotNull final ByteBuffer key) {
        try {
            switch (request.getMethod()) {
                case Request.METHOD_GET:
                    session.sendResponse(get(key));
                    break;
                case Request.METHOD_PUT:
                    session.sendResponse(put(request, key));
                    break;
                case Request.METHOD_DELETE:
                    session.sendResponse(delete(key));
                    break;
            }
        } catch (IOException ioException) {
            logger.error("Sending error", ioException);
        }
    }

    private void connectToAllReplicas(@NotNull HttpSession session,
                                      @NotNull Request request,
                                      @NotNull String replicas,
                                      @NotNull ByteBuffer key) {
        request.addHeader("X-Replicas-Finding");
        final Pair replicaParam = Pair.setAckFrom(replicas, topology.size());
        if (replicaParam == null) {
            logger.error("Ack/From illegal arguments");
            try {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            } catch (IOException ioException) {
                logger.error("Cant sending BAD_REQUEST", ioException);
            }
        }
        final List<String> nodes = topology.ackServers(replicaParam.getFrom(), key);
        final List<Response> responses;
        final Response output;
        try {
            switch (request.getMethod()) {
                case Request.METHOD_GET:
                    responses = getResponses(nodes, get(key), request);
                    output = ReplicasResponses.get(responses, replicaParam);
                    session.sendResponse(output);
                    break;
                case Request.METHOD_PUT:
                    responses = getResponses(nodes, put(request, key), request);
                    output = ReplicasResponses.put(responses, replicaParam);
                    session.sendResponse(output);
                    break;
                case Request.METHOD_DELETE:
                    responses = getResponses(nodes, delete(key), request);
                    output = ReplicasResponses.delete(responses, replicaParam);
                    session.sendResponse(output);
                    break;
                default:
                    break;
            }
        } catch (IOException ioException) {
            logger.error("Can't send resulting response.", ioException);
        }
    }

    private List<Response> getResponses(@NotNull final List<String> nodes,
                                           @NotNull final Response response,
                                           @NotNull final Request request) {
        final List<Response> responses = new ArrayList<>();
        nodes.forEach(node -> {
            if (topology.isMe(node)) {
                responses.add(response);
            } else {
                responses.add(proxy(node, request));
            }
        });
        return responses;
    }

    /**
     * Getting Entity by id.
     * <p>
     * 200 - ok
     * 400 - Empty id in param
     * 404 - No such element in dao
     * 500 - Internal error
     */
    @NotNull
    private Response get(@NotNull final ByteBuffer key) {
        try {
            final Value value = dao.getCell(key).getValue();
            Response response;
            if (value.isTombstone()){
                response = new Response(Response.OK, Response.EMPTY);
                response.addHeader("TOMBSTONE");
            } else {
                response = new Response(Response.OK, converterFromByteBuffer(value.getData()));
            }
            response.addHeader("TimeStamp" + value.getTimestamp());
            return response;
        } catch (NoSuchElementException e) {
            logger.error("Record not exist by id = {}", key);
            Response output = new Response(Response.NOT_FOUND, Response.EMPTY);
            return output;
        }
    }

    private Response put(@NotNull final Request request,
                         @NotNull final ByteBuffer key) {
        try {
            dao.upsert(key, ByteBuffer.wrap(request.getBody()));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (IOException e) {
            logger.error("Error whet upsert or sending response:", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    /**
     * Deleting entity from dao by id.
     */

    public Response delete(@NotNull final ByteBuffer key) {
        try {
            dao.remove(key);
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (IOException e) {
            logger.error("Error when deleting record", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response proxy(@NotNull final String node,
                           @NotNull final Request request) {
        try {
            request.addHeader("X-Replicas-Finding: " + node);
            return new Response(nodeToClient.get(node).invoke(request));
        } catch (IOException | InterruptedException | PoolException | HttpException e) {
            logger.error("Can`t proxy request: ", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private boolean checkId(@NotNull final String id, @NotNull final HttpSession session) {
        if (id.strip().isEmpty()) {
            try {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return true;
            } catch (IOException e) {
                logger.error(UNIVERSAL_MESSAGE, e);
                return false;
            }
        }
        return false;
    }

    @Override
    public void handleDefault(@NotNull final Request request, @NotNull final HttpSession session) {
        try {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        } catch (IOException e) {
            logger.error("handleDefault can`t send response", e);
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.error("Executor don`t wanna stop!!!", e);
            Thread.currentThread().interrupt();
        }
    }
}
