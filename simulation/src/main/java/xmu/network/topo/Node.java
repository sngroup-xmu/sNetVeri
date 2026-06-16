package xmu.network.topo;
import xmu.network.others.AS;
import xmu.network.others.IP;
import xmu.network.routes.RIB;
import xmu.network.routes.RouterIDs;

import java.util.*;

public class Node {

    private int nodeId;
    private byte role;
    private AS asNumber;
    private Map<Integer, Edge> edges=new HashMap<>(); //key: remoteNodeId
    private Map<IP, PhysicalNode> physicalNodeMap=new HashMap<>(); //key: routerId
    private Set<IP> deviceIpList=new HashSet<>();
    private RouterIDs routerIdList;
    private List<AggregateRoute> aggPolicyList=new ArrayList<>();

    private int preferenceExternal; //ebgp
    private int preferenceInternal; //ibgp
    private int preferenceLocal; //bgp
    private int maxLBNum;
    public RIB rib=new RIB();
    public Node(int nodeId, byte role, AS asNumber, Map<Integer, Edge> edges,Map<IP, PhysicalNode> physicalNodeMap,
                Set<IP> deviceIpList, List<AggregateRoute>aggPolicyList,int preferenceExternal,int preferenceInternal,int preferenceLocal,int maxLBNum) {
    this.nodeId=nodeId;
    this.role=role;
    this.asNumber=asNumber;
    this.edges=edges;
    this.physicalNodeMap=physicalNodeMap;
    this.deviceIpList=deviceIpList;
    this.routerIdList=new RouterIDs(physicalNodeMap.keySet());
    this.aggPolicyList=aggPolicyList;
    this.preferenceExternal=preferenceExternal;
    this.preferenceInternal=preferenceInternal;
    this.preferenceLocal=preferenceLocal;
    this.maxLBNum=maxLBNum;
    }




//    private Map<AS, List<IP>> as=new ConcurrentHashMap<>();
//    private Map<IP,AS> ipasMap=new HashMap<>();

    public int getNodeId(){
        return nodeId;
    }

    public Map<IP, PhysicalNode> getPhysicalNodeMap(){
        return physicalNodeMap;
    }
    public List<AggregateRoute> getAggregateRouteList() {
        return aggPolicyList;
    }

    public RouterIDs getRouterIdList(){
        return routerIdList;
    }

    public AS getAsNumber() {
        return asNumber;
    }
    public Map<Integer, Edge> getEdges() {
        return edges;
    }

    public Set<IP> getDeviceIpList() {
        return deviceIpList;
    }
    public int getPreferenceExternal() {
        return preferenceExternal;
    }

    public int getPreferenceLocal() {
        return preferenceLocal;
    }
    public int getMaxLBNum() {
        return maxLBNum;
    }

    public byte getRole() {
        return role;
    }
}
