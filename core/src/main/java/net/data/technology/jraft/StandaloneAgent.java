package net.data.technology.jraft;

import com.google.common.net.HostAndPort;
import com.vrg.rapid.Cluster;
import com.vrg.rapid.ClusterStatusChange;

import javax.annotation.Nullable;
import java.io.IOException;

public class StandaloneAgent {
    private final Logger LOG;
    private static final int SLEEP_INTERVAL_MS = 1000;
    private static final int MAX_TRIES = 400;
    final HostAndPort listenAddress;
    final HostAndPort seedAddress;
    @Nullable
    private Cluster cluster = null;

    StandaloneAgent(final HostAndPort listenAddress, final HostAndPort seedAddress, final LoggerFactory loggerFactory) {
        this.listenAddress = listenAddress;
        this.seedAddress = seedAddress;
        this.LOG = loggerFactory.getLogger(this.getClass());
    }

    public void startCluster() throws IOException, InterruptedException {
        // The first node X of the cluster calls .start(), the rest call .join(X)
        if (listenAddress.equals(seedAddress)) {
            cluster = new Cluster.Builder(listenAddress)
                    .start();

        } else {
            cluster = new Cluster.Builder(listenAddress)
                    .join(seedAddress);
        }
        cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE_PROPOSAL,
                this::onViewChangeProposal);
        cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE,
                this::onViewChange);
        cluster.registerSubscription(com.vrg.rapid.ClusterEvents.KICKED,
                this::onKicked);
    }

    /**
     * Executed whenever a Cluster VIEW_CHANGE_PROPOSAL event occurs.
     */
    void onViewChangeProposal(final ClusterStatusChange viewChange) {
        LOG.info("The condition detector has outputted a proposal: {}", viewChange);
        System.out.println("The condition detector has outputted a proposal: " + viewChange);
    }

    /**
     * Executed whenever a Cluster KICKED event occurs.
     */
    void onKicked(final ClusterStatusChange viewChange) {
        LOG.info("We got kicked from the network: {}", viewChange);
        System.out.println("We got kicked from the network: " + viewChange);
    }

    /**
     * Executed whenever a Cluster VIEW_CHANGE event occurs.
     */
    void onViewChange(final ClusterStatusChange viewChange) {
        LOG.info("View change detected: {}", viewChange);
        System.out.println("View change detected: " + viewChange);
    }

    /**
     * Prints the current membership
     */
    private void printClusterMembership() {
        if (cluster != null) {
            LOG.info("Node {} -- cluster size {}", listenAddress, cluster.getMembershipSize());
            System.out.println("Node " + listenAddress + " -- cluster size " + cluster.getMembershipSize());
        }
    }
}
