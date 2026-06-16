package xmu.network.topo;

public class Edge {
    private int remoteNodeId;
    private int exportPolicyId;
    private int importPolicyId;
    private int remoteExportPolicyId;
    private int remoteImportPolicyId;

    public Edge(int remoteNodeId, int exportPolicyId, int importPolicyId, int remoteExportPolicyId, int remoteImportPolicyId) {
        this.remoteNodeId = remoteNodeId;
        this.exportPolicyId = exportPolicyId;
        this.importPolicyId = importPolicyId;
        this.remoteExportPolicyId = remoteExportPolicyId;
        this.remoteImportPolicyId = remoteImportPolicyId;
    }

    public int getRemoteNodeId() {
        return remoteNodeId;
    }
    public int getExportPolicyId() {
        return exportPolicyId;
    }
    public int getRemoteImportPolicyId() {
        return remoteImportPolicyId;
    }
}
