package xmu.network.topo;


import xmu.network.others.AS;
import xmu.network.others.IP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PhysicalNode {
    private IP deviceIp;
    private List<Interface> interfaceList=new ArrayList<Interface>();

    private List<ImportRoute>importPolicyList=new ArrayList<>();
    private List<NetworkRoute>networkPolicyList=new ArrayList<>();
    private Map<IP, Peer> peerList=new HashMap<>(); // remoteIP
    private AS asNum;
    private IP routerId;

    public PhysicalNode(IP deviceIp,List<Interface> interfaceList,List<ImportRoute> importRouteList,List<NetworkRoute> networkRouteList,Map<IP, Peer> peerList,AS asNum,IP routerId) {
        this.deviceIp=deviceIp;
        this.interfaceList=interfaceList;
        this.importPolicyList=importRouteList;
        this.networkPolicyList=networkRouteList;
        this.peerList=peerList;
        this.asNum=asNum;
        this.routerId=routerId;
//        this.preferenceExternal=preferenceExternal;
//        this.preferenceInternal=preferenceInternal;
//        this.preferenceLocal=preferenceLocal;
    }

    public List<Interface> getInterfaceList() {
        return interfaceList;
    }

    public List<ImportRoute> getImportPolicyList() {
        return importPolicyList;
    }

    public List<NetworkRoute> getNetworkPolicyList() {
        return networkPolicyList;
    }

    public IP getRouterId() {
        return routerId;
    }
    public AS getAsNum() {
        return asNum;
    }

    public Map<IP, Peer> getPeerList() {
        return peerList;
    }
}
