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

import java.util.concurrent.ScheduledThreadPoolExecutor;

public class RaftContext {

    private final ServerStateManager serverStateManager;
    private final RpcListener rpcListener;
    private final LoggerFactory loggerFactory;
    private final RpcClientFactory rpcClientFactory;
    private final StateMachine stateMachine;
    private RaftParameters raftParameters;
    private ScheduledThreadPoolExecutor scheduledExecutor;
    private final int serverSize;
    private final String seedIp;
    private final int seedId;

    public RaftContext(ServerStateManager stateManager, StateMachine stateMachine, RaftParameters raftParameters, RpcListener rpcListener, LoggerFactory logFactory, RpcClientFactory rpcClientFactory, int serverSize, String seedIp, int seedId) {
        this(stateManager, stateMachine, raftParameters, rpcListener, logFactory, rpcClientFactory, serverSize, seedIp, seedId, null);
    }

    public RaftContext(ServerStateManager stateManager, StateMachine stateMachine, RaftParameters raftParameters, RpcListener rpcListener, LoggerFactory logFactory, RpcClientFactory rpcClientFactory, int serverSize, String seedIp, int seedId, ScheduledThreadPoolExecutor scheduledExecutor) {
        this.serverStateManager = stateManager;
        this.stateMachine = stateMachine;
        this.raftParameters = raftParameters;
        this.rpcClientFactory = rpcClientFactory;
        this.rpcListener = rpcListener;
        this.loggerFactory = logFactory;
        this.serverSize = serverSize;
        this.seedIp = seedIp;
        this.seedId = seedId;
        this.scheduledExecutor = scheduledExecutor;
        if (this.scheduledExecutor == null) {
            this.scheduledExecutor = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());
        }

        if (this.raftParameters == null) {
            this.raftParameters = new RaftParameters()
                    .withElectionTimeoutUpper(300)
                    .withElectionTimeoutLower(150)
                    .withHeartbeatInterval(75)
                    .withRpcFailureBackoff(25)
                    .withMaximumAppendingSize(100)
                    .withLogSyncBatchSize(1000)
                    .withLogSyncStoppingGap(100);
        }
    }

    public ServerStateManager getServerStateManager() {
        return serverStateManager;
    }

    public RpcListener getRpcListener() {
        return rpcListener;
    }

    public LoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    public RpcClientFactory getRpcClientFactory() {
        return rpcClientFactory;
    }

    public int getServerSize() {
        return this.serverSize;
        }

    public String getSeedIp() {
        return seedIp;
    }

    public int getSeedId() {
        return seedId;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    public RaftParameters getRaftParameters() {
        return raftParameters;
    }

    public ScheduledThreadPoolExecutor getScheduledExecutor() {
        return this.scheduledExecutor;
    }
}
