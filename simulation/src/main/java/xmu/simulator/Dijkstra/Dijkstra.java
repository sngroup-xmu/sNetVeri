package xmu.simulator.Dijkstra;


import xmu.input.CLIParser;
import xmu.network.Context;
import xmu.network.others.IP;
import xmu.network.others.Pair;
import xmu.network.others.Subnet;
import xmu.network.protocol.BGP;
import xmu.network.routes.BGPRoute;
import xmu.network.routes.RouterIDs;
import xmu.network.topo.Edge;
import xmu.network.topo.Node;
import xmu.util.Batch;
import xmu.util.IO;
import xmu.util.TimeStats;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class Dijkstra {

    public static void run(int batchSize, String filePath,int printType, Context context){

        if(printType!=CLIParser.WO_PRINT) {
            TimeStats.recordPrintTime(() -> IO.writeNode(filePath, context));
        }
        simulate(batchSize,filePath,context.getAllBgpPrefix(),printType,context);
        Map<Subnet,Set<Integer>>allaggPrefix=new HashMap<>();
        Map<Subnet,Set<Integer>> aggPrefix=hasAggregationRoutes(allaggPrefix,context);
        while(!aggPrefix.isEmpty()){
            System.out.println("start aggregation routes");
            simulate(batchSize,filePath,aggPrefix,printType,context);
            aggPrefix=hasAggregationRoutes(allaggPrefix,context);
        }
        if(printType!=CLIParser.WO_PRINT) {
            TimeStats.recordPrintTime(() -> IO.writePacketSpace(filePath, context));
            IO.fixJsonFiles(filePath);
        }
    }
    public static void simulate(int batchSize, String filePath,Map<Subnet,Set<Integer>> prefix,int printType,  Context context) {
        System.out.println("Dijkstra");
        List<Subnet> allbgpPrefix= new  ArrayList<>(prefix.keySet());
        List<Set<Subnet>> prefixBatch = Batch.sharding(batchSize,allbgpPrefix );
        prefixBatch.stream().forEach(batch->{
            batch.parallelStream().forEach(subnet -> {
                    compute(subnet, context,prefix.get(subnet));
            });

        if(printType!= CLIParser.WO_PRINT){
            TimeStats.recordPrintTime(() ->
                    IO.writeCompressedRoutes(filePath, batch, context)
            );
        }
            clean(batch, context);
        });

    }

    public static void print() {

    }

    public static void clean(Set<Subnet> batch, Context context) {
        batch.parallelStream().forEach(subnet -> {
            for (Node node : context.getNodeMap().values()) {
                node.rib.getBgpRoutes().remove(subnet);
            }
        });
    }


    public static Map<Subnet,Set<Integer>> hasAggregationRoutes(Map<Subnet,Set<Integer>>allaggPrefix, Context context){
        Map<Subnet,Set<Integer>> aggPrefix=new HashMap<>();
        for (Node node : context.getNodeMap().values()) {
            Map<Subnet,BGPRoute> aggregationRoutes=node.rib.getAggregationBGPRoutes();
            for (BGPRoute route : aggregationRoutes.values()) {
                if(route.isAdvertise()){
                    if(allaggPrefix.containsKey(route.getNetwork()) && allaggPrefix.get(route.getNetwork()).contains(node.getNodeId())){
                        continue;
                    }
                    node.rib.addBgpRoute(new BGPRoute(route));
                    aggPrefix.computeIfAbsent(route.getNetwork(),k->new HashSet<>()).add(node.getNodeId());
                    allaggPrefix.computeIfAbsent(route.getNetwork(),k->new HashSet<>()).add(node.getNodeId());
                }
                route.setAdvertise(false);
            }
        }
        return aggPrefix;
    }
    public static void compute(Subnet prefix,Context cxt,Set<Integer> nodeIdList) {
        Map<Integer, Dist> dist = new ConcurrentHashMap<>();
        Queue queue = new Queue();
        Map<Integer, Node> nodeMap = cxt.getNodeMap();
        nodeIdList.forEach(nodeId -> {
            Node node = nodeMap.get(nodeId);
            List<BGPRoute> bestRoutes = BGP.findBestRoute(prefix, node,cxt);
            dist.put(nodeId, new Dist(bestRoutes));
            if (bestRoutes != null) {
                queue.addAll(bestRoutes, nodeId);
            }
        });
        while (!queue.isEmpty()) {
            Pair<BGPRoute, Integer> first = queue.getFirst();
            Node nodeU = nodeMap.get(first.getValue());
            BGPRoute routeU = first.getKey();
            nodeU.getEdges().entrySet().forEach(entry -> {
                int nodeVId = entry.getKey();
                Node nodeV = nodeMap.get(nodeVId);
//                Map<IP, PhysicalNode> physicalNodeMap = nodeV.getPhysicalNodeMap();
                BGPRoute transRoute=BGP.trans(nodeU, nodeV, routeU, entry.getValue(),cxt);
                    if(transRoute!=null){
                        if (!dist.containsKey(nodeVId)) {
                            Dist d = new Dist(transRoute);
                            dist.put(nodeVId, d);
                            queue.add(transRoute, nodeVId);
                        } else if (!dist.get(nodeVId).contains(transRoute)) {
                            RouterIDs routerIds = transRoute.getRemoteRouterIdList();
                            IP minRouterId=routerIds.getMinRouterId();
                            BGP.changeAsPath(transRoute,cxt);
                            dist.get(nodeVId).add(transRoute);
                            queue.add(transRoute, nodeVId);
                        }
                        else{
                            List<BGPRoute> routes = BGP.findBestRoute(prefix, nodeV,cxt);
                            List<BGPRoute> withdrawRoutes = new ArrayList<>();
                            List<BGPRoute> updateRoutes = new ArrayList<>();
                            BGP.withdrawSet(dist.get(nodeV.getNodeId()).getBestRoutes(), routes, withdrawRoutes, updateRoutes);
                            if (!withdrawRoutes.isEmpty()) {
                                withdrawRoutes.forEach(route -> {
                                    try {
                                        withdrawRoute( dist, nodeV, route, queue,cxt);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                            }
                            if (!updateRoutes.isEmpty()) {
                                dist.get(nodeVId).addAll(updateRoutes);
                                queue.addAll(updateRoutes, nodeVId);
                            }
                        }
                    }
            });
        }
    }


    public static void withdrawRoute( Map<Integer, Dist> dist, Node node, BGPRoute route, Queue queue,Context context ) {
        int nodeId=node.getNodeId();
        if(dist.get(nodeId)!=null){
            BGPRoute withdrawRoute = new BGPRoute(route);
            dist.get(nodeId).remove(withdrawRoute);
            if(queue.contains(withdrawRoute,nodeId)){
                queue.remove(withdrawRoute,nodeId);
                return;
            }
            node.getEdges().entrySet().forEach(
                    entry -> {
                        Edge edge=entry.getValue();
                        Node remoteNode=context.getNode(edge.getRemoteNodeId());
                        BGPRoute transRoute= null;
                        transRoute = BGP.trans(node,remoteNode,withdrawRoute,edge,context);
                        if(transRoute!=null){
                            remoteNode.rib.removeBgpRoute(transRoute);
                        }
                        else {
                            return;
                        }
                        if(dist.get(remoteNode.getNodeId())!=null){
                            BGP.changeAsPath(transRoute,context);
                            if (dist.get(remoteNode.getNodeId()).getBestRoutes().contains(transRoute)) {
                                List<BGPRoute> withdrawRoutes = new ArrayList<>();
                                List<BGPRoute> updateRoutes = new ArrayList<>();
                                BGP.withdrawSet(dist.get(remoteNode.getNodeId()).getBestRoutes(), BGP.findBestRoute(transRoute.getNetwork(),remoteNode,context), withdrawRoutes, updateRoutes);
                                if (!withdrawRoutes.isEmpty()) {
                                    withdrawRoutes.forEach(r -> {
                                        withdrawRoute( dist, remoteNode, r, queue,context);
                                    });
                                }
                                if (!updateRoutes.isEmpty()) {
                                    dist.get(remoteNode.getNodeId()).addAll(updateRoutes);
                                    queue.addAll(updateRoutes, remoteNode.getNodeId());
                                }
                            }
                        }
                    }
            );
        }
    }
}
