package xmu.network.topo;


import xmu.network.others.IP;

public class Peer {
    private IP remoteDeviceIp;
    private IP localInterfaceIp;
    private int localInterfaceId;
    private IP remoteInterfaceIp;
    private int remoteInterfaceId;
    public Peer(IP remoteDeviceIp, IP localInterfaceIp, int localInterfaceId, IP remoteInterfaceIp, int remoteInterfaceId) {
        this.remoteDeviceIp = remoteDeviceIp;
        this.localInterfaceIp = localInterfaceIp;
        this.localInterfaceId = localInterfaceId;
        this.remoteInterfaceIp = remoteInterfaceIp;
        this.remoteInterfaceId = remoteInterfaceId;
    }

    public int getlocalInterfaceId() {
        return localInterfaceId;
    }

    public IP getLocalInterfaceIp() {
        return localInterfaceIp;
    }

    public IP getRemoteInterfaceIp() {
        return remoteInterfaceIp;
    }
}
