/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  The ASF licenses
 * this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.data.technology.jraft;

import net.data.technology.jraft.extensions.AsyncUtility;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class KVStore implements StateMachine {

    private final Map<String, String> map = new ConcurrentHashMap<>();
    private final int port;
    private final org.apache.logging.log4j.Logger logger;
    private AsynchronousServerSocketChannel listener;
    private ExecutorService executorService;
    private final long startCount;
    private final long endCount;
    private long startTimestamp;
    private long endTimestamp;

    public KVStore(int listeningPort) {
        this(listeningPort, 100, 4000);
    }

    public KVStore(int listeningPort, long startCount, long endCount) {
        this.port = listeningPort;
        this.logger = LogManager.getLogger(getClass());
        this.startCount = startCount;
        this.endCount = endCount;
    }

    public void start(RaftMessageSender messageSender) {
        logger.info("Starting kvstore on port " + port);
        int processors = Runtime.getRuntime().availableProcessors();
        executorService = Executors.newFixedThreadPool(processors);
        try {
            AsynchronousChannelGroup channelGroup = AsynchronousChannelGroup.withThreadPool(executorService);
            this.listener = AsynchronousServerSocketChannel.open(channelGroup);
            this.listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            this.listener.bind(new InetSocketAddress(this.port));
            logger.info("abbout to call acceptrequests, listenr is " + listener);
            this.acceptRequests();
        } catch (IOException exception) {
            logger.error("failed to start the listener due to io error", exception);
        }
    }

    public void stop() {
        logger.info("About to call stop in kvstore");
        if (this.listener != null) {
            try {
                this.listener.close();
            } catch (IOException e) {
                logger.info("failed to close the listener socket", e);
            }

            this.listener = null;
        }

        if (this.executorService != null) {
            this.executorService.shutdown();
            this.executorService = null;
        }
    }

    @Override
    public void commit(long logIndex, byte[] data) {
        String message = new String(data, StandardCharsets.UTF_8);
        System.out.printf("commit: %d\t%s\n", logIndex, message);
        String[] split = message.split(":");
        if (split.length == 2) {
            map.put(split[0], split[1]);
        }

        if (startTimestamp == 0 && logIndex >= startCount) {
            startTimestamp = System.currentTimeMillis();
        } else if (endTimestamp == 0 && logIndex >= endCount) {
            endTimestamp = System.currentTimeMillis();

            double diff = (endTimestamp - startTimestamp) / 1000.0;
            double throughPut = (endCount - startCount) / diff;
            logger.info(String.format("Throughput: %.5f entries committed per second", throughPut));
        }
    }

    @Override
    public void rollback(long logIndex, byte[] data) {

    }

    @Override
    public void preCommit(long logIndex, byte[] data) {

    }

    @Override
    public void exit(int code) {
        System.exit(code);
    }

    private void acceptRequests() {
        try {
            this.listener.accept(null, AsyncUtility.handlerFrom(
                    (AsynchronousSocketChannel connection, Object ctx) -> {
                        readRequest(connection);
                        acceptRequests();
                    },
                    (Throwable error, Object ctx) -> {
                        logger.error("accepting a new connection failed, will still keep accepting more requests", error);
                        acceptRequests();
                    }));
        } catch (Exception exception) {
            logger.error("failed to accept new requests, will retry", exception);
            this.acceptRequests();
        }
    }

    private void readRequest(AsynchronousSocketChannel connection) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        try {
            AsyncUtility.readFromChannel(connection, buffer, null, handlerFrom((Integer bytesRead, Object ctx) -> {
                if (bytesRead < 4) {
                    logger.info("failed to read the request header from client socket");
                    closeSocket(connection);
                } else {
                    try {
                        logger.debug("request header read, try to read the message");
                        int bodySize = 0;
                        for (int i = 0; i < 4; ++i) {
                            int value = buffer.get(i);
                            bodySize = bodySize | (value << (i * 8));
                        }

                        if (bodySize > 1024) {
                            sendResponse(connection, "Bad Request");
                            return;
                        }

                        ByteBuffer bodyBuffer = ByteBuffer.allocate(bodySize);
                        readBody(connection, bodyBuffer);
                    } catch (Throwable runtimeError) {
                        // if there are any conversion errors, we need to close the client socket to prevent more errors
                        closeSocket(connection);
                        logger.info("message reading/parsing error", runtimeError);
                    }
                }
            }, connection));
        } catch (Exception readError) {
            logger.info("failed to read more request from client socket", readError);
            closeSocket(connection);
        }
    }

    private void readBody(AsynchronousSocketChannel connection, ByteBuffer bodyBuffer) {
        try {
            AsyncUtility.readFromChannel(connection, bodyBuffer, null, handlerFrom((Integer bytesRead, Object ctx) -> {
                if (bytesRead < bodyBuffer.limit()) {
                    logger.info("failed to read the request body from client socket");
                    System.out.println("failed to read the request body from client socket");
                    closeSocket(connection);
                } else {
                    String message = new String(bodyBuffer.array(), StandardCharsets.UTF_8);
                    CompletableFuture<String> future = new CompletableFuture<String>();
                    future.whenCompleteAsync((String ack, Throwable err) -> {
                        if (err != null) {
                            sendResponse(connection, err.getMessage());
                        } else {
                            sendResponse(connection, ack);
                        }
                    });
                    processMessage(message, future);
                }
            }, connection));
        } catch (Exception readError) {
            logger.info("failed to read more request from client socket", readError);
            System.out.println("failed to read more request from client socket" + readError);
            closeSocket(connection);
        }
    }

    private void processMessage(String message, CompletableFuture<String> future) {
        if ("status".equalsIgnoreCase(message)) {
            future.complete("Committed Messages:" + map + "\n");
        } else {
            String res = map.getOrDefault(message, "KeyNotFound") + "\n";
            System.out.print("Value: " + res);
            // key is the message
            future.complete(res);
        }
    }

    private void sendResponse(AsynchronousSocketChannel connection, String message) {
        byte[] resp = message.getBytes(StandardCharsets.UTF_8);
        int respSize = resp.length;
        ByteBuffer respBuffer = ByteBuffer.allocate(respSize + 4);
        for (int i = 0; i < 4; ++i) {
            int value = (respSize >> (i * 8));
            respBuffer.put((byte) (value & 0xFF));
        }

        respBuffer.put(resp);
        respBuffer.flip();
        try {
            AsyncUtility.writeToChannel(connection, respBuffer, null, handlerFrom((Integer bytesWrite, Object ctx) -> {
                if (bytesWrite < respBuffer.limit()) {
                    logger.info("failed to write all data back to response channel");
                    closeSocket(connection);
                } else {
                    readRequest(connection);
                }
            }, connection));
        } catch (Exception writeError) {
            logger.info("failed to write response to client socket", writeError);
            closeSocket(connection);
        }
    }

    private <V, A> CompletionHandler<V, A> handlerFrom(BiConsumer<V, A> completed, AsynchronousSocketChannel connection) {
        return AsyncUtility.handlerFrom(completed, (Throwable error, A attachment) -> {
            this.logger.info("socket server failure", error);
            if (connection != null) {
                closeSocket(connection);
            }
        });
    }

    private void closeSocket(AsynchronousSocketChannel connection) {
        try {
            connection.close();
        } catch (IOException ex) {
            this.logger.info("failed to close client socket", ex);
        }
    }
}
