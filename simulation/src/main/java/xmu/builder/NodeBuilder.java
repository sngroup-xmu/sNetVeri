package xmu.builder;

import xmu.config.topo.*;
import xmu.network.Context;
import xmu.network.others.AS;
import xmu.network.others.IP;
import xmu.network.others.Subnet;
import xmu.network.topo.*;

import java.util.*;

public class NodeBuilder {

    public static Set<IP> buildDeviceIpSet(List<String> deviceIp){
        Set<IP> deviceipSet = new HashSet<IP>();
        deviceIp.forEach(ip->{
            IP deviceIP = new IP(ip);
            deviceipSet.add(deviceIP);
        });
        return deviceipSet;
    }
    public static  Map<Integer, Edge> buildEdgeMap(List<EdgeConfig> edgeConfigs){
        Map<Integer, Edge> edgeMap = new HashMap<Integer, Edge>();
        edgeConfigs.forEach(edgeConfig->{
            Edge edge=new Edge(edgeConfig.remoteNodeId,edgeConfig.localExportPolicyId,edgeConfig.localImportPolicyId,edgeConfig.remoteExportPolicyId,edgeConfig.remoteImportPolicyId);
            edgeMap.put(edgeConfig.remoteNodeId,edge);
        });
        return edgeMap;
    }
    public static List<Interface> buildInterfaceList(List<InterfaceConfig> interfaceConfigs, Context ctx){
        List<Interface> interfaceList=new ArrayList<>();
        interfaceConfigs.forEach(interfaceConfig->{
            IP deviceIp =new IP(interfaceConfig.deviceIp);
            Subnet segment=new Subnet(interfaceConfig.segment);
            IP peerIp;
            if ("null".equalsIgnoreCase(interfaceConfig.peerIp))
            {
                peerIp=IP.NULL;
            }
            else {
                peerIp=new IP(interfaceConfig.peerIp);
            }
            IP nextHop;
            if ("null".equalsIgnoreCase(interfaceConfig.nextHop))
            {
                nextHop=IP.NULL;
            }
            else {
                nextHop=new IP(interfaceConfig.nextHop);
            }
            int ifaceNameId=ctx.registerInterfaceName(interfaceConfig.interfaceName);
            byte protocol=Context.protocolToByte(interfaceConfig.type);
            Interface iface= new Interface(deviceIp,segment,peerIp,ifaceNameId,nextHop,protocol);
            interfaceList.add(iface);
        });
        return interfaceList;
    }
    public static List<AggregateRoute> buildAggregateRouteList(List<AggregateRouteConfig> routeConfigs){
        List<AggregateRoute> aggregateRouteList=new ArrayList<>();
        routeConfigs.forEach(routeConfig->{
            Subnet segment=new Subnet(routeConfig.segment);
            boolean detailSuppress=false;
            if("true".equalsIgnoreCase(routeConfig.detailSuppress)){
                detailSuppress=true;
            }
            AggregateRoute aggregateRoute=new AggregateRoute(segment,routeConfig.attributePolicyId,routeConfig.suppressPolicyId,detailSuppress);
            aggregateRouteList.add(aggregateRoute);
        });
        return aggregateRouteList;
    }
    public static List<NetworkRoute> buildNetworkRouteList(List<NetworkRouteConfig> routeConfigs){
        List<NetworkRoute> networkRouteList=new ArrayList<>();
        routeConfigs.forEach(routeConfig->{
            Subnet segment=new Subnet(routeConfig.segment);
            NetworkRoute networkRoute=new NetworkRoute(segment,routeConfig.id);
            networkRouteList.add(networkRoute);
        });
        return networkRouteList;
    }
    public static List<ImportRoute> buildImportRouteList(List<ImportRouteConfig> routeConfigs){
        List<ImportRoute> importRouteList=new ArrayList<>();
        routeConfigs.forEach(routeConfig->{
            byte protocol =Context.protocolToByte(routeConfig.protocol);
            ImportRoute importRoute=new ImportRoute(protocol,routeConfig.id);
            importRouteList.add(importRoute);
        });
        return importRouteList;
    }
    public static Map<IP, Peer> buildPeerList(List<PeerConfig> peerConfigs,Context ctx){
        Map<IP, Peer> peerMap=new HashMap<>();
        peerConfigs.forEach(peerConfig->{
            IP remoteDeviceIp =new IP(peerConfig.remoteIp);
            IP localInterfaceIp=new IP(peerConfig.localPeerIp);
            IP remoteInterfaceIp=new IP(peerConfig.remotePeerIp);
            int localInterfaceId=ctx.registerInterfaceName(peerConfig.localInterfaceName);
            int remoteInterfaceId=ctx.registerInterfaceName(peerConfig.remoteInterfaceName);
            Peer peer=new Peer(remoteDeviceIp,localInterfaceIp,localInterfaceId,remoteInterfaceIp,remoteInterfaceId);
            peerMap.put(remoteDeviceIp,peer);
        });
        return peerMap;
    }
    public static Map<IP, PhysicalNode> buildPhysicalNodeMap(List<PhysicalNodeConfig> physicalNodeConfigs,Context ctx){
        Map<IP, PhysicalNode> physicalNodeMap=new HashMap<>();
        physicalNodeConfigs.forEach(physicalNodeConfig->{
            IP deviceIp =new IP(physicalNodeConfig.deviceIp);
            List<Interface> interfaceList=buildInterfaceList(physicalNodeConfig.interfaceList,ctx);
            List<NetworkRoute> networkRouteList=buildNetworkRouteList(physicalNodeConfig.networkRouteList);
            List<ImportRoute> importRouteList=buildImportRouteList(physicalNodeConfig.importRouteList);
            Map<IP,Peer> peerList=buildPeerList(physicalNodeConfig.peerList,ctx);

            AS asnum=new AS(physicalNodeConfig.asNum);
            IP routerId=new IP(physicalNodeConfig.routerId);
            ctx.registerAsIP(routerId,asnum);
            ctx.registerRouterId(routerId,deviceIp);
            PhysicalNode physicalNode=new PhysicalNode(deviceIp,interfaceList,importRouteList,
                    networkRouteList,peerList,asnum,routerId);
//            ctx.registerPhysicalNode(physicalNode);
            physicalNodeMap.put(routerId,physicalNode);
        });
        return physicalNodeMap;
    }

    public static void build(List<NodeConfig> nodeConfigs, Context cxt)
    {
        nodeConfigs.forEach(nodeConfig->{
            AS asnum=new AS(nodeConfig.asNum);
            Map<Integer, Edge> edgeMap=buildEdgeMap(nodeConfig.edgeList);
            Map<IP,PhysicalNode> physicalNodeMap=buildPhysicalNodeMap(nodeConfig.physicalNodeList,cxt);
            Set<IP> deviceIpSet=buildDeviceIpSet(nodeConfig.deviceIp);
            PhysicalNodeConfig firstPhysicalNodeConfig=nodeConfig.physicalNodeList.get(0);
            List<AggregateRoute>aggregateRouteList=buildAggregateRouteList(firstPhysicalNodeConfig.aggregateRouteList);
            int preferenceExternal=Integer.parseInt(firstPhysicalNodeConfig.preferenceExternal);
            int preferenceInternal=Integer.parseInt(firstPhysicalNodeConfig.preferenceInternal);
            int preferenceLocal=Integer.parseInt(firstPhysicalNodeConfig.preferenceLocal);
            int maxLBNum=Integer.parseInt(firstPhysicalNodeConfig.maxLbNum);
            Node node =new Node(nodeConfig.nodeId, Context.roleToByte(nodeConfig.deviceRole),asnum,edgeMap,
                    physicalNodeMap,deviceIpSet,aggregateRouteList,preferenceExternal , preferenceInternal,preferenceLocal,maxLBNum);
            cxt.registerNode(node);
            cxt.registerRouterIdNode(node);
        });
    }
}
