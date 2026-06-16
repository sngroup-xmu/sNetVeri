package xmu.network.topo;



import xmu.network.others.IP;
import xmu.network.others.Subnet;

public class Interface {
    private IP deviceIp;
    private Subnet segment;
    private IP peerIp;
    private  int interfaceNameId;
    private IP nextHop;
    private byte protocol;

    public Interface(IP deviceIp, Subnet segment, IP peerIp, int interfaceNameId,IP nextHop, byte protocol){
        this.deviceIp = deviceIp;
        this.segment = segment;
        this.peerIp = peerIp;
        this.nextHop = nextHop;
        this.protocol = protocol;
        this.interfaceNameId = interfaceNameId;
    }

    public Subnet getSegment() {
        return segment;
    }
    public IP getPeerIp() {
        return peerIp;
    }
    public int getInterfaceNameId() {
        return interfaceNameId;
    }
    public IP getNextHop() {
        return nextHop;
    }
    public byte getProtocol() {
        return protocol;
    }
}
