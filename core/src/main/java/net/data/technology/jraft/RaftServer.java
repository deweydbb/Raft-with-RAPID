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

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.vrg.rapid.*;
import com.vrg.rapid.messaging.IMessagingClient;
import com.vrg.rapid.messaging.IMessagingServer;
import com.vrg.rapid.messaging.impl.GrpcClient;
import com.vrg.rapid.messaging.impl.GrpcServer;
import com.vrg.rapid.monitoring.IEdgeFailureDetectorFactory;
import com.vrg.rapid.pb.EdgeStatus;
import com.vrg.rapid.pb.Endpoint;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class RaftServer implements RaftMessageHandler {

    private static final Comparator<Long> indexComparator = new Comparator<Long>() {

        @Override
        public int compare(Long arg0, Long arg1) {
            return (int) (arg1 - arg0);
        }
    };
    private final RaftContext context;
    private ScheduledFuture<?> scheduledElection;
    // Map is not thread safe
    private final Map<Integer, PeerServer> peers = new HashMap<Integer, PeerServer>();
    private final Set<Integer> votedServers = new HashSet<>();
    private ServerRole role;
    private ServerState state;
    private int leader;
    private int serverSize;
    private final int id;
    private int votesGranted;
    private boolean electionCompleted;
    private final SequentialLogStore logStore;
    private final StateMachine stateMachine;
    private final Logger logger;
    private final Random random;
    private final Callable<Void> electionTimeoutTask;
    private ClusterConfiguration config;
    private long quickCommitIndex;
    private final CommittingThread commitingThread;

    // fields for extended messages
    private PeerServer serverToJoin = null;
    private boolean configChanging = false;
    private boolean catchingUp = false;
    private int steppingDown = 0;
    // end fields for extended messages

    // rapid variables
    Cluster cluster;
    private long configId;

    public RaftServer(RaftContext context) {
        this.id = context.getServerStateManager().getServerId();
        this.state = context.getServerStateManager().readState();
        this.logStore = context.getServerStateManager().loadLogStore();
        this.config = null;
        this.stateMachine = context.getStateMachine();
        this.serverSize = context.getServerSize();
        this.votesGranted = 0;
        this.leader = -1;
        this.electionCompleted = false;
        this.context = context;
        this.logger = context.getLoggerFactory().getLogger(this.getClass());
        this.random = new Random(Calendar.getInstance().getTimeInMillis());
        this.electionTimeoutTask = () -> {
            handleElectionTimeout();
            return null;
        };


        if (this.state == null) {
            this.state = new ServerState();
            this.state.setTerm(0);
            this.state.setVotedFor(-1);
            this.state.setCommitIndex(0);
        }

        this.quickCommitIndex = this.state.getCommitIndex();
        this.commitingThread = new CommittingThread(this);
        this.role = ServerRole.Follower;
        new Thread(this.commitingThread).start();

        this.logger.info("Server %d started", this.id);
    }

    public void startRapid() {
        joinCluster(5);
        ClusterConfiguration newConfig = new ClusterConfiguration(cluster.getMemberlist(), this.logStore.getFirstAvailableIndex());
        reconfigure(newConfig);

        if (leader == -1) {
            // we get append entries, set leader
            this.restartElectionTimer();
            if (leader != -1) {
                stopElectionTimer();
            }
        }

        this.logger.debug("End of start rapid, leader is %d", leader);
    }

    private void joinCluster(int numRetries) {
        try {
            String localIp = "127.0.0.1";
            String seedIp = context.getSeedIp();
            if (!seedIp.equals("127.0.0.1") && !seedIp.equals("localhost")) {
                DatagramSocket datagramSocket = new DatagramSocket();
                datagramSocket.connect(InetAddress.getByName("8.8.8.8"), 12345);
                localIp = datagramSocket.getLocalAddress().getHostAddress();
            }

            logger.info("Joining cluster with localIp: %s and seedIp: %s", localIp, seedIp);

            final HostAndPort listenAddress = HostAndPort.fromString(String.format("%s:85%02d", localIp, id));
            final Endpoint listenEndpoint = Endpoint.newBuilder()
                    .setHostname(ByteString.copyFromUtf8(localIp))
                    .setPort(8500 + id)
                    .build();
            final HostAndPort seedAddress = HostAndPort.fromString(String.format("%s:85%02d", context.getSeedIp(), context.getSeedId()));

            Settings settings = new Settings();
            settings.setGrpcProbeTimeoutMs(1000);  // default is 1000
            settings.setFailureDetectorIntervalInMs(1000); // default is 1000

            SharedResources sharedResources = new SharedResources(listenEndpoint);
            IMessagingClient messagingClient = new GrpcClient(listenEndpoint, sharedResources, settings);
            IMessagingServer messagingServer = new GrpcServer(listenEndpoint, sharedResources, settings.getUseInProcessTransport());
            IEdgeFailureDetectorFactory factory = new PingPongFailureDetector.Factory(listenEndpoint, messagingClient);

            if (this.id == context.getSeedId()) {
                cluster = new Cluster.Builder(listenAddress)
                        .useSettings(settings)
                        .setMessagingClientAndServer(messagingClient, messagingServer)
                        .setEdgeFailureDetectorFactory(factory)
                        .start();
            } else {
                // doesn't return until been accepted into cluster
                cluster = new Cluster.Builder(listenAddress)
                        .useSettings(settings)
                        .setMessagingClientAndServer(messagingClient, messagingServer)
                        .setEdgeFailureDetectorFactory(factory)
                        .join(seedAddress);
            }
            cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE, this::onViewChange);

            configId = cluster.getConfigurationId();
            logger.debug("set configId = %d", configId);
        } catch (IOException | InterruptedException e) {
            logger.warning("Failed to join cluster with exception {}", e);
            if (numRetries == 0) {
                throw new RuntimeException("Failed to join cluster with exception " + e);
            }
            joinCluster(numRetries - 1);
        }
    }

    /**
     * Executed whenever a Cluster VIEW_CHANGE event occurs.
     */
    public synchronized void onViewChange(final ClusterStatusChange viewChange) {
        logger.info("View change detected: %s", viewChange);
        System.out.println("View change detected: " + viewChange);
        System.out.println("view change id " + viewChange.getConfigurationId());

        this.configId = viewChange.getConfigurationId();
        logger.debug("set configId = %d", configId);

        ClusterConfiguration newConfig = new ClusterConfiguration(viewChange.getMembership(), this.logStore.getFirstAvailableIndex());

        reconfigure(newConfig);

        if (newConfig.getServers().size() > this.serverSize) {
            logger.info("Updating server size from %d to %d in onViewChange", this.serverSize, newConfig.getServers().size());
            this.serverSize = newConfig.getServers().size();
        }

        // if server is leader and new servers have joined, send appendEntries requests to let them know who the leader is
        if (role == ServerRole.Leader && viewChange.getDelta().stream().map(NodeStatusChange::getStatus).anyMatch(x -> x == EdgeStatus.UP)) {
            // hey I am the leader
            this.requestAppendEntries();
        }

        // we need to know who current leader is and if new leader is not in new config, then we start randomized election timeout, and once that timeouts we call handle election timeout method
        if (!peers.containsKey(leader) && this.id != this.leader) {
            logger.debug("Last known leader %d is not in new config, starting election timeout", leader);
            // last known leader is NOT in the new config, start randomized election timeout
            restartElectionTimer();
        }
    }

    public RaftMessageSender createMessageSender() {
        return new RaftMessageSenderImpl(this);
    }

    @Override
    public RaftResponseMessage processRequest(RaftRequestMessage request) {
        this.logger.debug(
                "Receive a %s message from %d with LastLogIndex=%d, LastLogTerm=%d, EntriesLength=%d, CommitIndex=%d and Term=%d",
                request.getMessageType().toString(),
                request.getSource(),
                request.getLastLogIndex(),
                request.getLastLogTerm(),
                request.getLogEntries() == null ? 0 : request.getLogEntries().length,
                request.getCommitIndex(),
                request.getTerm());

        RaftResponseMessage response = null;
        if (request.getMessageType() == RaftMessageType.AppendEntriesRequest) {
            response = this.handleAppendEntriesRequest(request);
        } else if (request.getMessageType() == RaftMessageType.RequestVoteRequest) {
            response = this.handleVoteRequest(request);
        } else if (request.getMessageType() == RaftMessageType.ClientRequest) {
            response = this.handleClientRequest(request);
        } else {
            // extended requests
            response = this.handleExtendedMessages(request);
        }

        if (response != null) {
            this.logger.debug(
                    "Response back a %s message to %d with Accepted=%s, Term=%d, NextIndex=%d",
                    response.getMessageType().toString(),
                    response.getDestination(),
                    String.valueOf(response.isAccepted()),
                    response.getTerm(),
                    response.getNextIndex());
        }

        return response;
    }

    private synchronized RaftResponseMessage handleAppendEntriesRequest(RaftRequestMessage request) {
        // we allow the server to be continue after term updated to save a round message
        this.updateTerm(request.getTerm());

        // Reset stepping down value to prevent this server goes down when leader crashes after sending a LeaveClusterRequest
        if (this.steppingDown > 0) {
            this.steppingDown = 2;
        }

        if (request.getTerm() == this.state.getTerm()) {
            if (this.role == ServerRole.Candidate) {
                this.becomeFollower();
            } else if (this.role == ServerRole.Leader) {
                this.logger.error("Receive AppendEntriesRequest from another leader(%d) with same term, there must be a bug, server exits", request.getSource());
                this.stateMachine.exit(-1);
            }
        }

        RaftResponseMessage response = new RaftResponseMessage();
        response.setMessageType(RaftMessageType.AppendEntriesResponse);
        response.setTerm(this.state.getTerm());
        response.setSource(this.id);
        response.setDestination(request.getSource());
        response.setConfigId(this.configId);
        response.setServerSize(this.serverSize);

        // After a snapshot the request.getLastLogIndex() may less than logStore.getStartingIndex() but equals to logStore.getStartingIndex() -1
        // In this case, log is Okay if request.getLastLogIndex() == lastSnapshot.getLastLogIndex() && request.getLastLogTerm() == lastSnapshot.getLastTerm()
        boolean configOkay = this.configId == request.getConfigId();
        logger.info("handleAppendEntriesRequest this config id %d", this.configId);
        logger.info("handleAppendEntriesRequest request config id %d", request.getConfigId());
        boolean logOkay = request.getLastLogIndex() == 0 ||
                (request.getLastLogIndex() < this.logStore.getFirstAvailableIndex() &&
                        request.getLastLogTerm() == this.termForLastLog(request.getLastLogIndex()));
        if (request.getTerm() < this.state.getTerm() || !configOkay || !logOkay) {
            response.setAccepted(false);
            response.setNextIndex(this.logStore.getFirstAvailableIndex());
            return response;
        }

        // The role is Follower and log is okay now
        if (request.getLogEntries() != null && request.getLogEntries().length > 0) {
            // write the logs to the store, first of all, check for overlap, and skip them
            LogEntry[] logEntries = request.getLogEntries();
            long index = request.getLastLogIndex() + 1;
            int logIndex = 0;
            while (index < this.logStore.getFirstAvailableIndex() &&
                    logIndex < logEntries.length &&
                    logEntries[logIndex].getTerm() == this.logStore.getLogEntryAt(index).getTerm()) {
                logIndex++;
                index++;
            }

            // dealing with overwrites
            while (index < this.logStore.getFirstAvailableIndex() && logIndex < logEntries.length) {
                LogEntry oldEntry = this.logStore.getLogEntryAt(index);
                this.stateMachine.rollback(index, oldEntry.getValue());

                LogEntry logEntry = logEntries[logIndex];
                this.logStore.writeAt(index, logEntry);
                this.stateMachine.preCommit(index, logEntry.getValue());

                index += 1;
                logIndex += 1;
            }

            // append the new log entries
            while (logIndex < logEntries.length) {
                LogEntry logEntry = logEntries[logIndex++];
                long indexForEntry = this.logStore.append(logEntry);
                this.stateMachine.preCommit(indexForEntry, logEntry.getValue());
            }
        }

        if (request.getServerSize() > this.serverSize) {
            logger.info("Updating server size from %d to %d in append entries", this.serverSize, response.getServerSize());
            this.serverSize = request.getServerSize();
        }

        this.leader = request.getSource();
        this.logger.info("We are about to call stopElectionTimer");
        stopElectionTimer();
        this.commit(request.getCommitIndex());
        response.setAccepted(true);
        response.setNextIndex(request.getLastLogIndex() + (request.getLogEntries() == null ? 0 : request.getLogEntries().length) + 1);
        return response;
    }

    private synchronized RaftResponseMessage handleVoteRequest(RaftRequestMessage request) {
        // we allow the server to be continue after term updated to save a round message
        this.updateTerm(request.getTerm());

        RaftResponseMessage response = new RaftResponseMessage();
        response.setMessageType(RaftMessageType.RequestVoteResponse);
        response.setSource(this.id);
        response.setDestination(request.getSource());
        response.setTerm(this.state.getTerm());
        response.setConfigId(this.configId);
        response.setServerSize(this.serverSize);

        boolean sameConfigId = this.configId == request.getConfigId();
        logger.info("handleVoteRequest this config id %d", this.configId);
        logger.info("handleVoteRequest request config id %d", request.getConfigId());
        boolean logOkay = request.getLastLogTerm() > this.logStore.getLastLogEntry().getTerm() ||
                (request.getLastLogTerm() == this.logStore.getLastLogEntry().getTerm() &&
                        this.logStore.getFirstAvailableIndex() - 1 <= request.getLastLogIndex());
        boolean grant = sameConfigId && request.getTerm() == this.state.getTerm() && logOkay && (this.state.getVotedFor() == request.getSource() || this.state.getVotedFor() == -1);
        response.setAccepted(grant);
        if (grant) {
            this.state.setVotedFor(request.getSource());
            this.context.getServerStateManager().persistState(this.state);
        }

        return response;
    }

    private RaftResponseMessage handleClientRequest(RaftRequestMessage request) {
        RaftResponseMessage response = new RaftResponseMessage();
        response.setMessageType(RaftMessageType.AppendEntriesResponse);
        response.setSource(this.id);
        response.setDestination(this.leader);
        response.setTerm(this.state.getTerm());
        response.setServerSize(this.serverSize);

        long term;
        synchronized (this) {
            if (this.role != ServerRole.Leader) {
                response.setAccepted(false);
                return response;
            }

            term = this.state.getTerm();
        }

        LogEntry[] logEntries = request.getLogEntries();
        if (logEntries != null && logEntries.length > 0) {
            for (int i = 0; i < logEntries.length; ++i) {
                this.stateMachine.preCommit(this.logStore.append(new LogEntry(term, logEntries[i].getValue())), logEntries[i].getValue());
            }
        }

        // Urgent commit, so that the commit will not depend on heartbeat
        this.requestAppendEntries();
        response.setAccepted(true);
        response.setNextIndex(this.logStore.getFirstAvailableIndex());
        return response;
    }

    private synchronized void handleElectionTimeout() {
        if (this.role == ServerRole.Leader) {
            this.logger.error("A leader should never encounter election timeout, illegal application state, stop the application");
            this.stateMachine.exit(-1);
            return;
        }

        this.logger.debug("Election timeout, change to Candidate");
        this.state.increaseTerm();
        this.state.setVotedFor(-1);
        this.role = ServerRole.Candidate;
        this.votedServers.clear();
        this.votesGranted = 0;
        this.electionCompleted = false;
        this.context.getServerStateManager().persistState(this.state);
        this.requestVote();

        // restart the election timer if this is not yet a leader
        if (this.role != ServerRole.Leader) {
            this.restartElectionTimer();
        }
    }

    private void requestVote() {
        // vote for self
        this.logger.info("requestVote started with term %d", this.state.getTerm());
        this.state.setVotedFor(this.id);
        this.context.getServerStateManager().persistState(this.state);
        this.votesGranted += 1;
        this.votedServers.add(this.id);

        // Am i the only server in the cluster
        if (this.votesGranted >= serverSize / 2 + 1) {
            this.electionCompleted = true;
            this.becomeLeader();
            logger.info("We have just become the leader and are exiting requestVote");
            return;
        }

        for (PeerServer peer : this.peers.values()) {
            RaftRequestMessage request = new RaftRequestMessage();
            request.setMessageType(RaftMessageType.RequestVoteRequest);
            request.setDestination(peer.getId());
            request.setSource(this.id);
            request.setLastLogIndex(this.logStore.getFirstAvailableIndex() - 1);
            request.setLastLogTerm(this.termForLastLog(this.logStore.getFirstAvailableIndex() - 1));
            request.setTerm(this.state.getTerm());
            request.setConfigId(this.configId);
            request.setServerSize(this.serverSize);
            this.logger.debug("send %s to server %d with term %d, config %d", RaftMessageType.RequestVoteRequest.toString(), peer.getId(), this.state.getTerm(), request.getConfigId());
            peer.SendRequest(request).whenCompleteAsync(this::handlePeerResponse, this.context.getScheduledExecutor());
        }
    }

    private void requestAppendEntries() {
        //if (this.peers.size() == 0) {
        if (serverSize == 1) {
            this.commit(this.logStore.getFirstAvailableIndex() - 1);
            return;
        }

        for (PeerServer peer : this.peers.values()) {
            this.requestAppendEntries(peer);
        }
    }

    private boolean requestAppendEntries(PeerServer peer) {
        if (peer.makeBusy()) {
            peer.SendRequest(this.createAppendEntriesRequest(peer))
                    .whenCompleteAsync((RaftResponseMessage response, Throwable error) -> {
                        try {
                            handlePeerResponse(response, error);
                        } catch (Throwable err) {
                            this.logger.error("Uncaught exception %s", err.toString());
                        }
                    }, this.context.getScheduledExecutor());
            return true;
        }

        this.logger.debug("Server %d is busy, skip the request", peer.getId());
        return false;
    }

    private synchronized void handlePeerResponse(RaftResponseMessage response, Throwable error) {
        if (error != null) {
            this.logger.info("peer response error: %s", error.getMessage());
            return;
        }

        this.logger.debug(
                "Receive a %s message from peer %d with Result=%s, Term=%d, NextIndex=%d",
                response.getMessageType().toString(),
                response.getSource(),
                String.valueOf(response.isAccepted()),
                response.getTerm(),
                response.getNextIndex());
        // If term is updated no need to proceed
        if (this.updateTerm(response.getTerm())) {
            return;
        }

        // Ignore the response that with lower term for safety
        if (response.getTerm() < this.state.getTerm()) {
            this.logger.info("Received a peer response from %d that with lower term value %d v.s. %d", response.getSource(), response.getTerm(), this.state.getTerm());
            return;
        }

        if (response.getServerSize() > this.serverSize) {
            logger.info("Updating server size from %d to %d in handlePeerResponse", this.serverSize, response.getServerSize());
            this.serverSize = response.getServerSize();
        }

        if (response.getMessageType() == RaftMessageType.RequestVoteResponse) {
            this.handleVotingResponse(response);
        } else if (response.getMessageType() == RaftMessageType.AppendEntriesResponse) {
            this.handleAppendEntriesResponse(response);
        } else {
            this.logger.error("Received an unexpected message %s for response, system exits.", response.getMessageType().toString());
            this.stateMachine.exit(-1);
        }
    }

    private void handleAppendEntriesResponse(RaftResponseMessage response) {
        PeerServer peer = this.peers.get(response.getSource());
        if (peer == null) {
            this.logger.info("the response is from an unkonw peer %d", response.getSource());
            return;
        }

        // If there are pending logs to be synced or commit index need to be advanced, continue to send appendEntries to this peer
        boolean needToCatchup = true;
        if (response.isAccepted()) {
            synchronized (peer) {
                peer.setNextLogIndex(response.getNextIndex());
                peer.setMatchedIndex(response.getNextIndex() - 1);
            }

            // try to commit with this response
            ArrayList<Long> matchedIndexes = new ArrayList<>(this.peers.size() + 1);
            matchedIndexes.add(this.logStore.getFirstAvailableIndex() - 1);
            for (PeerServer p : this.peers.values()) {
                matchedIndexes.add(p.getMatchedIndex());
            }

            matchedIndexes.sort(indexComparator);
            this.commit(matchedIndexes.get((serverSize) / 2));

            needToCatchup = peer.clearPendingCommit() || response.getNextIndex() < this.logStore.getFirstAvailableIndex();
        } else {
            synchronized (peer) {
                // Improvement: if peer's real log length is less than was assumed, reset to that length directly
                if (response.getNextIndex() > 0 && peer.getNextLogIndex() > response.getNextIndex()) {
                    peer.setNextLogIndex(response.getNextIndex());
                } else {
                    peer.setNextLogIndex(peer.getNextLogIndex() - 1);
                }
            }
        }

        // This may not be a leader anymore, such as the response was sent out long time ago
        // and the role was updated by UpdateTerm call
        // Try to match up the logs for this peer
        if (this.role == ServerRole.Leader && needToCatchup) {
            this.requestAppendEntries(peer);
        }
    }

    private void handleVotingResponse(RaftResponseMessage response) {
        if (this.votedServers.contains(response.getSource())) {
            this.logger.info("Duplicate vote from %d form term %d", response.getSource(), this.state.getTerm());
            return;
        }

        this.votedServers.add(response.getSource());
        if (this.electionCompleted) {
            this.logger.info("Election completed, will ignore the voting result from this server");
            return;
        }

        if (response.isAccepted()) {
            this.votesGranted += 1;
        }

        if (this.votedServers.size() >= serverSize) {
            this.electionCompleted = true;
        }

        // got a majority set of granted votes
        if (this.votesGranted >= serverSize / 2 + 1) {
            this.logger.info("Server is elected as leader for term %d", this.state.getTerm());
            this.electionCompleted = true;
            this.becomeLeader();
        }
    }

    private void restartElectionTimer() {
        if (this.scheduledElection != null) {
            this.scheduledElection.cancel(false);
        }

        int electionTimeout = 500 + this.random.nextInt(1500);
        // Schedule when election happens based off randomized timeout
        this.logger.info("About to call schedule(this electionTimeoutTask in restartElectionTimer");
        this.scheduledElection = this.context.getScheduledExecutor().schedule(this.electionTimeoutTask, electionTimeout, TimeUnit.MILLISECONDS);
        logger.info("Returning from restartElection Timeout");
    }

    private void stopElectionTimer() {
        if (this.scheduledElection == null) {
            return;
        }

        this.scheduledElection.cancel(false);
        this.scheduledElection = null;
        logger.info("Canceled election timer");
    }

    private void becomeLeader() {
        Instant instant = Instant.now();
        logger.info("I have become leader at timestamp: %d%09d", instant.getEpochSecond(), instant.getNano());
        this.stopElectionTimer();
        this.role = ServerRole.Leader;
        this.leader = this.id;
        this.serverToJoin = null;
        for (PeerServer server : this.peers.values()) {
            server.setNextLogIndex(this.logStore.getFirstAvailableIndex());
            server.setFree();
        }

        this.requestAppendEntries();
        System.out.println("I have become the leader");
    }

    private void becomeFollower() {
        this.serverToJoin = null;
        this.role = ServerRole.Follower;
        logger.info("Becoming follower, restarting election timer");
        this.restartElectionTimer();
    }

    private boolean updateTerm(long term) {
        if (term > this.state.getTerm()) {
            this.state.setTerm(term);
            this.state.setVotedFor(-1);
            this.electionCompleted = false;
            this.votesGranted = 0;
            this.votedServers.clear();
            this.context.getServerStateManager().persistState(this.state);
            this.becomeFollower();
            return true;
        }

        return false;
    }

    private void commit(long targetIndex) {
        if (targetIndex > this.quickCommitIndex) {
            this.quickCommitIndex = targetIndex;

            // if this is a leader notify peers to commit as well
            // for peers that are free, send the request, otherwise, set pending commit flag for that peer
            if (this.role == ServerRole.Leader) {
                for (PeerServer peer : this.peers.values()) {
                    if (!this.requestAppendEntries(peer)) {
                        peer.setPendingCommit();
                    }
                }
            }
        }

        if (this.logStore.getFirstAvailableIndex() - 1 > this.state.getCommitIndex() && this.quickCommitIndex > this.state.getCommitIndex()) {
            this.commitingThread.moreToCommit();
        }
    }

    private RaftRequestMessage createAppendEntriesRequest(PeerServer peer) {
        long currentNextIndex = 0;
        long commitIndex = 0;
        long lastLogIndex = 0;
        long term = 0;

        synchronized (this) {
            currentNextIndex = this.logStore.getFirstAvailableIndex();
            commitIndex = this.quickCommitIndex;
            term = this.state.getTerm();
        }

        synchronized (peer) {
            if (peer.getNextLogIndex() == 0) {
                peer.setNextLogIndex(currentNextIndex);
            }

            lastLogIndex = peer.getNextLogIndex() - 1;
        }

        if (lastLogIndex >= currentNextIndex) {
            this.logger.error("Peer's lastLogIndex is too large %d v.s. %d, server exits", lastLogIndex, currentNextIndex);
            this.stateMachine.exit(-1);
        }

        long lastLogTerm = this.termForLastLog(lastLogIndex);
        long endIndex = Math.min(currentNextIndex, lastLogIndex + 1 + context.getRaftParameters().getMaximumAppendingSize());
        LogEntry[] logEntries = (lastLogIndex + 1) >= endIndex ? null : this.logStore.getLogEntries(lastLogIndex + 1, endIndex);
        this.logger.debug(
                "An AppendEntries Request for %d with LastLogIndex=%d, LastLogTerm=%d, EntriesLength=%d, CommitIndex=%d and Term=%d",
                peer.getId(),
                lastLogIndex,
                lastLogTerm,
                logEntries == null ? 0 : logEntries.length,
                commitIndex,
                term);
        RaftRequestMessage requestMessage = new RaftRequestMessage();
        requestMessage.setMessageType(RaftMessageType.AppendEntriesRequest);
        requestMessage.setSource(this.id);
        requestMessage.setDestination(peer.getId());
        requestMessage.setLastLogIndex(lastLogIndex);
        requestMessage.setLastLogTerm(lastLogTerm);
        requestMessage.setLogEntries(logEntries);
        requestMessage.setCommitIndex(commitIndex);
        requestMessage.setTerm(term);
        requestMessage.setConfigId(configId);
        requestMessage.setServerSize(this.serverSize);
        return requestMessage;
    }

    private void reconfigure(ClusterConfiguration newConfig) {
        this.logger.debug(
                "system is reconfigured to have %d servers, last config index: %d, this config index: %d",
                newConfig.getServers().size(),
                newConfig.getLastLogIndex(),
                newConfig.getLogIndex());
        List<Integer> serversRemoved = new LinkedList<Integer>();
        List<ClusterServer> serversAdded = new LinkedList<ClusterServer>();
        for (ClusterServer s : newConfig.getServers()) {
            // loop through new servers and if peers list does not contain server id and ..(not itself). then add new server
            if (!this.peers.containsKey(s.getId()) && s.getId() != this.id) {
                serversAdded.add(s);
            }
        }

        // checks that peer is in new config and if not add to serversRemoved
        for (Integer id : this.peers.keySet()) {
            if (newConfig.getServer(id.intValue()) == null) {
                serversRemoved.add(id);
            }
        }
        // TODO david thinks not necessary
        if (newConfig.getServer(this.id) == null) {
            serversRemoved.add(this.id);
        }

        for (ClusterServer server : serversAdded) {
            if (server.getId() != this.id) {
                PeerServer peer = new PeerServer(server, context);
                peer.setNextLogIndex(this.logStore.getFirstAvailableIndex());
                this.peers.put(server.getId(), peer);
                this.logger.info("server %d is added to cluster", peer.getId());
            }
        }

        for (Integer id : serversRemoved) {
            if (id == this.id) {
                // this server is removed from cluster
                this.context.getServerStateManager().saveClusterConfiguration(newConfig);
                this.logger.info("server has been removed from cluster, step down");
                this.stateMachine.exit(0);
                return;
            }

            PeerServer peer = this.peers.get(id);
            if (peer == null) {
                this.logger.info("peer %d cannot be found in current peer list", id);
            } else {
                this.peers.remove(id);
                this.logger.info("server %d is removed from cluster", id.intValue());
            }
        }

        this.config = newConfig;
    }

    private synchronized RaftResponseMessage handleExtendedMessages(RaftRequestMessage request) {
        if (request.getMessageType() == RaftMessageType.GetClusterRequest) {
          return this.handleGetClusterRequest(request);
        } else {
            this.logger.error("receive an unknown request %s, for safety, step down.", request.getMessageType().toString());
            this.stateMachine.exit(-1);
        }

        return null;
    }

    private synchronized void handleExtendedResponse(RaftResponseMessage response, Throwable error) {
        this.logger.debug(
                "Receive an extended %s message from peer %d with Result=%s, Term=%d, NextIndex=%d",
                response.getMessageType().toString(),
                response.getSource(),
                String.valueOf(response.isAccepted()),
                response.getTerm(),
                response.getNextIndex());
    }

    private RaftResponseMessage handleGetClusterRequest(RaftRequestMessage request) {
        logger.info("start of handleGetClusterRequest");
        RaftResponseMessage response = new RaftResponseMessage();
        response.setMessageType(RaftMessageType.GetClusterResponse);
        response.setSource(this.id);
        response.setDestination(this.leader);
        response.setTerm(this.state.getTerm());
        response.setConfigId(configId);
        response.setServerSize(this.serverSize);

        if (config != null) {
            LogEntry[] logEntries = new LogEntry[1];
            logEntries[0] = new LogEntry(this.state.getTerm(), config.toBytes(), LogValueType.Configuration);
            response.setLogEntries(logEntries);
            response.setAccepted(true);
        } else {
            response.setAccepted(false);
        }

        logger.info("handleGetClusterRequest returning cluster: " + config);
        return response;
    }

    private long termForLastLog(long logIndex) {
        if (logIndex == 0) {
            return 0;
        }

        return this.logStore.getLogEntryAt(logIndex).getTerm();
    }

    // implements addserver removeserver and entries
    static class RaftMessageSenderImpl implements RaftMessageSender {
        // lower level, as take info and convert it to bytes etc...
        // method later tries to send to current leader
        private RaftServer server;
        private Map<Integer, RpcClient> rpcClients;

        RaftMessageSenderImpl(RaftServer server) {
            this.server = server;
            this.rpcClients = new ConcurrentHashMap<Integer, RpcClient>();
        }

        @Override
        public CompletableFuture<Boolean> appendEntries(byte[][] values) {
            if (values == null || values.length == 0) {
                return CompletableFuture.completedFuture(false);
            }

            LogEntry[] logEntries = new LogEntry[values.length];
            for (int i = 0; i < values.length; ++i) {
                logEntries[i] = new LogEntry(0, values[i]);
            }

            RaftRequestMessage request = new RaftRequestMessage();
            request.setMessageType(RaftMessageType.ClientRequest);
            request.setLogEntries(logEntries);
            request.setServerSize(server.serverSize);
            return this.sendMessageToLeader(request);
        }

        private CompletableFuture<Boolean> sendMessageToLeader(RaftRequestMessage request) {
            int leaderId = this.server.leader;
            ClusterConfiguration config = this.server.config;
            if (leaderId == -1) {
                return CompletableFuture.completedFuture(false);
            }

            if (leaderId == this.server.id) {
                return CompletableFuture.completedFuture(this.server.processRequest(request).isAccepted());
            }

            CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
            RpcClient rpcClient = this.rpcClients.get(leaderId);
            if (rpcClient == null) {
                ClusterServer leader = config.getServer(leaderId);
                if (leader == null) {
                    result.complete(false);
                    return result;
                }

                rpcClient = this.server.context.getRpcClientFactory().createRpcClient(leader.getEndpoint());
                this.rpcClients.put(leaderId, rpcClient);
            }

            rpcClient.send(request).whenCompleteAsync((RaftResponseMessage response, Throwable err) -> {
                if (err != null) {
                    this.server.logger.info("Received an rpc error %s while sending a request to server (%d)", err.getMessage(), leaderId);
                    result.complete(false);
                } else {
                    result.complete(response.isAccepted());
                }
            }, this.server.context.getScheduledExecutor());

            return result;
        }
    }

    static class CommittingThread implements Runnable {

        private RaftServer server;
        private Object conditionalLock;

        CommittingThread(RaftServer server) {
            this.server = server;
            this.conditionalLock = new Object();
        }

        // external function that notifies via thread when more to commit
        void moreToCommit() {
            synchronized (this.conditionalLock) {
                this.conditionalLock.notify();
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    long currentCommitIndex = server.state.getCommitIndex();
                    // quick commit index stored in server state as parameter
                    while (server.quickCommitIndex <= currentCommitIndex
                            || currentCommitIndex >= server.logStore.getFirstAvailableIndex() - 1) {
                        synchronized (this.conditionalLock) {
                            this.conditionalLock.wait();
                        }

                        currentCommitIndex = server.state.getCommitIndex();
                    }
                    // if server thinks it has commited more things and there are more log entries than the current commit index ,
                    while (currentCommitIndex < server.quickCommitIndex && currentCommitIndex < server.logStore.getFirstAvailableIndex() - 1) {
                        currentCommitIndex += 1;
                        LogEntry logEntry = server.logStore.getLogEntryAt(currentCommitIndex);
                        server.stateMachine.commit(currentCommitIndex, logEntry.getValue());

                        server.state.setCommitIndex(currentCommitIndex);
                    }

                    server.context.getServerStateManager().persistState(server.state);
                } catch (Throwable error) {
                    server.logger.error("error %s encountered for committing thread, which should not happen, according to this, state machine may not have further progress, stop the system", error, error.getMessage());
                    server.stateMachine.exit(-1);
                }
            }
        }

    }
}
