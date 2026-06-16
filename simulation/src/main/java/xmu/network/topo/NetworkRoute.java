package xmu.network.topo;

import xmu.network.others.Subnet;
public class NetworkRoute {
    private Subnet segment;
    private int networkPolicyId;

    public NetworkRoute(Subnet segment, int networkPolicyId) {
        this.segment = segment;
        this.networkPolicyId = networkPolicyId;
    }

    public Subnet getSegment() {
        return segment;
    }
    public int getNetworkPolicyId() {
        return networkPolicyId;
    }
}
