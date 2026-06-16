package xmu.network.topo;


import xmu.network.others.Subnet;

public class AggregateRoute {
    private Subnet segment;
    private int attributePolicyId;
    private int suppressPolicyId;
    private boolean detailSuppress;

    public AggregateRoute(Subnet segment, int attributePolicyId, int suppressPolicyId, boolean detailSuppress) {
        this.segment = segment;
        this.attributePolicyId = attributePolicyId;
        this.suppressPolicyId = suppressPolicyId;
        this.detailSuppress = detailSuppress;
    }

    public Subnet getSegment() {
        return segment;
    }
    public int getAttributePolicyId() {
        return attributePolicyId;
    }
    public int getSuppressPolicyId() {
        return suppressPolicyId;
    }
    public boolean isDetailSuppress() {
        return detailSuppress;
    }
}
