// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;

import java.util.Objects;

/**
 * Capacity calculation for docker hosts.
 * <p>
 * The calculations is based on an immutable copy of nodes that represents
 * all capacities in the system - i.e. all nodes in the node repo give or take.
 *
 * @author smorgrav
 */
public class DockerHostCapacity {

    private final NodeList allNodes;
    private final HostResourcesCalculator hostResourcesCalculator;

    public DockerHostCapacity(NodeList allNodes, HostResourcesCalculator hostResourcesCalculator) {
        this.allNodes = Objects.requireNonNull(allNodes, "allNodes must be non-null");
        this.hostResourcesCalculator = Objects.requireNonNull(hostResourcesCalculator, "hostResourcesCalculator must be non-null");
    }

    /** Returns the allocation skew of this host */
    public double skew(Node host) {
        NodeResources free = freeCapacityOf(host, false);
        return Node.skew(host.flavor().resources(), free);
    }

    int compareWithoutInactive(Node hostA, Node hostB) {
        int result = compare(freeCapacityOf(hostB, true), freeCapacityOf(hostA, true));
        if (result != 0) return result;

        // If resources are equal we want to assign to the one with the most IPaddresses free
        return freeIPs(hostB) - freeIPs(hostA);
    }

    private int compare(NodeResources a, NodeResources b) {
        return NodeResourceComparator.defaultOrder().compare(a, b);
    }

    /**
     * Checks the node capacity and free ip addresses to see
     * if we could allocate a flavor on the docker host.
     */
    boolean hasCapacity(Node dockerHost, NodeResources requestedCapacity) {
        return freeCapacityOf(dockerHost, false).satisfies(requestedCapacity) && freeIPs(dockerHost) > 0;
    }

    /**
     * Number of free (not allocated) IP addresses assigned to the dockerhost.
     */
    int freeIPs(Node dockerHost) {
        return dockerHost.ipAddressPool().findUnused(allNodes).size();
    }

    /**
     * Calculate the remaining capacity of a host.
     *
     * @param host the host to find free capacity of.
     * @return a default (empty) capacity if not a docker host, otherwise the free/unallocated/rest capacity
     */
    public NodeResources freeCapacityOf(Node host) {
        return freeCapacityOf(host, false);
    }

    NodeResources freeCapacityOf(Node host, boolean excludeInactive) {
        // Only hosts have free capacity
        if (host.type() != NodeType.host) return new NodeResources(0, 0, 0, 0);
        NodeResources hostResources = hostResourcesCalculator.availableCapacityOf(host.flavor().resources());

        // Subtract used resources without taking disk speed into account since existing allocations grandfathered in
        // may not use reflect the actual disk speed (as of May 2019). This (the 3 diskSpeed assignments below)
        // can be removed when all node allocations accurately reflect the true host disk speed
        return allNodes.childrenOf(host).asList().stream()
                .filter(node -> !(excludeInactive && isInactiveOrRetired(node)))
                .map(node -> node.flavor().resources().anySpeed())
                .reduce(hostResources.anySpeed(), NodeResources::subtract)
                .withDiskSpeed(host.flavor().resources().diskSpeed());
    }

    private static boolean isInactiveOrRetired(Node node) {
        if (node.state() == Node.State.inactive) return true;
        if (node.allocation().isPresent() && node.allocation().get().membership().retired()) return true;
        return false;
    }

}
