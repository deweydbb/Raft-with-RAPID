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

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Peer server in the same cluster for local server
 * this represents a peer for local server, it could be a leader, however, if local server is not a leader, though it has a list of peer servers, they are not used
 * @author Data Technology LLC
 *
 */
public class PeerServer {

    private ClusterServer clusterConfig;
    private RpcClient rpcClient;
    private AtomicInteger busyFlag;
    private AtomicInteger pendingCommitFlag;
    private long nextLogIndex;
    private long matchedIndex;
    private Executor executor;

    public PeerServer(ClusterServer server, RaftContext context) {
        this.clusterConfig = server;
        this.rpcClient = context.getRpcClientFactory().createRpcClient(server.getEndpoint());
        this.busyFlag = new AtomicInteger(0);
        this.pendingCommitFlag = new AtomicInteger(0);
        this.nextLogIndex = 1;
        this.matchedIndex = 0;
        this.executor = context.getScheduledExecutor();
    }

    public int getId() {
        return this.clusterConfig.getId();
    }

    public boolean makeBusy() {
        return this.busyFlag.compareAndSet(0, 1);
    }

    public void setFree() {
        this.busyFlag.set(0);
    }

    public long getNextLogIndex() {
        return nextLogIndex;
    }

    public void setNextLogIndex(long nextLogIndex) {
        this.nextLogIndex = nextLogIndex;
    }

    public long getMatchedIndex() {
        return this.matchedIndex;
    }

    public void setMatchedIndex(long matchedIndex) {
        this.matchedIndex = matchedIndex;
    }

    public void setPendingCommit() {
        this.pendingCommitFlag.set(1);
    }

    public boolean clearPendingCommit() {
        return this.pendingCommitFlag.compareAndSet(1, 0);
    }

    public CompletableFuture<RaftResponseMessage> SendRequest(RaftRequestMessage request) {
        boolean isAppendRequest = request.getMessageType() == RaftMessageType.AppendEntriesRequest;
        return this.rpcClient.send(request)
                .thenComposeAsync((RaftResponseMessage response) -> {
                    if (isAppendRequest) {
                        this.setFree();
                    }

                    return CompletableFuture.completedFuture(response);
                }, this.executor)
                .exceptionally((Throwable error) -> {
                    if (isAppendRequest) {
                        this.setFree();
                    }

                    throw new RpcException(error, request);
                });
    }
}
