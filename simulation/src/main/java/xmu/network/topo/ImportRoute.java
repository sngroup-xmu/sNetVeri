package xmu.network.topo;

public class ImportRoute {
    private byte protocol; //1 direct 0 static
    private int importPolicyId;

    public ImportRoute(byte protocol, int importPolicyId) {
        this.protocol = protocol;
        this.importPolicyId = importPolicyId;
    }
    public byte getProtocol() {
        return protocol;
    }
    public int getImportPolicyId() {
        return importPolicyId;
    }
}
