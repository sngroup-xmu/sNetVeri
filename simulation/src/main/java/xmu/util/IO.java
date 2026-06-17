package xmu.util;



import xmu.network.Context;
import xmu.network.others.IP;
import xmu.network.others.Subnet;
import xmu.network.protocol.BGP;
import xmu.network.routes.BGPRoute;
import xmu.network.routes.Route;
import xmu.network.routes.RouterIDs;
import xmu.network.topo.Edge;
import xmu.network.topo.Node;
import xmu.network.topo.Peer;
import xmu.network.topo.PhysicalNode;


import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class IO {

    public static void writeNode(String filePath,Context context){
        try {
            //  创建 result 目录
            Path dirPath = Paths.get(filePath, "results");
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path torPath = dirPath.resolve("edge_devices");
            Path nodePath= dirPath.resolve("devices.json");
            Path topoPath= dirPath.resolve("topology.json");
            boolean nodeFileExists = Files.exists(nodePath );
            boolean topoFileExists = Files.exists(topoPath );
            int j=0;
            Set<String> printedNodePairs = new HashSet<>();
            for (Node node : context.getNodeMap().values()) {
                j++;
                try (BufferedWriter writer = new BufferedWriter(
                        new FileWriter(torPath.toFile(), true))) {
                    if(node.getRole()==Context.TOR||node.getRole()==Context.ROUTE){
                        writer.write("Node"+node.getNodeId()+"\n");
                    }
                }
                try (BufferedWriter writer = new BufferedWriter(
                        new FileWriter(nodePath.toFile(), true))) {
                    if(!nodeFileExists){
                        writer.write("{\n");
                        nodeFileExists = true;
                    }
                   writer.write("   \"Node"+node.getNodeId()+"\": [\n");
                   int i=0;
                   for (IP deviceIp:node.getDeviceIpList()){
                       i++;
                       if(i==node.getDeviceIpList().size()){
                           writer.write("       \""+deviceIp.toString()+"\"\n");
                       }
                       else {
                           writer.write("       \""+deviceIp.toString()+"\",\n");
                       }
                   }
                   if(j==context.getNodeMap().size()){
                       writer.write("   ]\n");
                       writer.write("}\n");
                   }
                   else {
                       writer.write("   ],\n");
                   }
                }
                try (BufferedWriter writer = new BufferedWriter(
                        new FileWriter(topoPath.toFile(), true))) {
                    if(!topoFileExists){
                        writer.write("[\n");
                        topoFileExists = true;
                    }

                    for(Edge edge:node.getEdges().values()){
                        Node remoteNode= context.getNode(edge.getRemoteNodeId());
                        int id1 = node.getNodeId();
                        int id2 = remoteNode.getNodeId();
                        if(node.getRole()>=remoteNode.getRole()){
                            String pairKey = Math.min(id1, id2) + "-" + Math.max(id1, id2);
                            // 已经输出过则跳过
                            if (printedNodePairs.contains(pairKey)) {
                                continue;
                            }
                            printedNodePairs.add(pairKey);
                            writer.write("  {\n");
                            writer.write("      \"dst_node\": \"Node"+node.getNodeId()+"\",\n");
                            writer.write("      \"dst_port\": \"Node"+node.getNodeId()+"->"+"Node"+remoteNode.getNodeId()+"\",\n");
                            writer.write("      \"src_node\": \"Node"+remoteNode.getNodeId()+"\",\n");
                            writer.write("      \"src_port\": \"Node"+remoteNode.getNodeId()+"->"+"Node"+node.getNodeId()+"\"\n");
                            writer.write("  },\n");
                        }
                    }
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }
    public static  Map<RouterIDs,List<Integer>> compressedRoutes( Subnet subnet, Node node, Context context)  {

        Map<IP,List<BGPRoute>> ecmpBgpRoutes=BGP.findECMPRoutes(subnet,node);
        Map<Integer,Route>compressedRoutes=new HashMap<>();
        for (Map.Entry<IP,List<BGPRoute>> entry : ecmpBgpRoutes.entrySet()) {
            IP routerId=entry.getKey();
            List<BGPRoute> routes=entry.getValue();
            if(node.rib.getDirectRoutes().containsKey(routerId)){
                if(node.rib.getDirectRoutes().get(routerId).containsKey(subnet)){
                    continue;
                }
            }
            else if(node.rib.getStaticRoutes().containsKey(routerId)){
                if(node.rib.getStaticRoutes().get(routerId).containsKey(subnet)){
                    continue;
                }
            }
            for (BGPRoute route : routes) {
                if(!route.getRemoteRouterIdList().isEmpty()){
                    Node remoteNode=context.getNode(route.getRemoteRouterIdList().getMinRouterId());
                    if(compressedRoutes.containsKey(remoteNode.getNodeId())){
                        Route compressedRoute=compressedRoutes.get(remoteNode.getNodeId());
                        compressedRoute.getRouterIdList().add(routerId);
                    }
                    else {
                        Route compressedRoute=new Route(route);
                        compressedRoutes.put(remoteNode.getNodeId(),compressedRoute);
                    }
                }
            }
        }
        Map<RouterIDs,List<Integer>> compressedResults=new HashMap<>();
        for (Map.Entry<Integer,Route> entry : compressedRoutes.entrySet()) {
            Route route=entry.getValue();
            int remoteNodeId=entry.getKey();
            compressedResults.computeIfAbsent(route.getRouterIdList(),k->new ArrayList<>()).add(remoteNodeId);
        }
        return compressedResults;
    }

    public static void writePacketSpace(String filePath,Context context)
    {
        try {
            //  创建 result 目录
            Path dirPath = Paths.get(filePath, "results");
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path packetPath = dirPath.resolve("packet_space.json");

            boolean packetFileExists = Files.exists(packetPath );

            try (BufferedWriter writer = new BufferedWriter(
                    new FileWriter(packetPath.toFile(), true))) {
                // 4️如果文件不存在 → 写表头
                if (!packetFileExists) {
                    writer.write("[\n");
                }
                for(Node node:context.getNodeMap().values()){
                    Map<IP, Map<Subnet,List<Route>>> directRoutes=node.rib.getDirectRoutes();
                    Map<IP, Map<Subnet,List<Route>>> staticRoutes=node.rib.getStaticRoutes();
                    for (Map.Entry<IP,Map<Subnet,List<Route>>> entry : directRoutes.entrySet()) {
                        IP routerId=entry.getKey();
                        Map<Subnet,List<Route>> routes=entry.getValue();
                        for (Map.Entry<Subnet,List<Route>> routeEntry : routes.entrySet()) {
                            List<Route> sortedRoutes=routeEntry.getValue().stream().sorted().collect(Collectors.toList());
                            for (Route route : sortedRoutes) {
                                String preifix=route.getDestination().getBaseAddress().toString();
                                if (!context.getAllBgpPrefix().containsKey(route.getDestination())) continue;
                                int prefixLength=route.getDestination().getPrefixLength();
                                IP deviceIp=context.getDeviceIP(routerId);

                                writer.write("  {\n");
                                writer.write("    \"prefix\": \"" + preifix + "\",\n");
                                writer.write("    \"prefix_len\": " + prefixLength + ",\n");
                                writer.write("    \"host_name\": \"Node"+node.getNodeId()+"\",\n");
                                writer.write("    \"device_name\": \"" + deviceIp.toString() + "\"\n");
                                writer.write("  },\n");
                            }
                        }
                    }
                    for (Map.Entry<IP,Map<Subnet,List<Route>>> entry : staticRoutes.entrySet()) {
                        IP routerId=entry.getKey();
                        Map<Subnet,List<Route>> routes=entry.getValue();
                        for (Map.Entry<Subnet,List<Route>> routeEntry : routes.entrySet()) {
                            if(directRoutes.containsKey(routerId)&&directRoutes.get(routerId).containsKey(routeEntry.getKey())){
                                continue;
                            }
                            List<Route> sortedRoutes=routeEntry.getValue().stream().sorted().collect(Collectors.toList());
                            for (Route route : sortedRoutes) {
                                String preifix=route.getDestination().getBaseAddress().toString();
                                if (!context.getAllBgpPrefix().containsKey(route.getDestination())) continue;
                                int prefixLength=route.getDestination().getPrefixLength();
                                IP deviceIp=context.getDeviceIP(routerId);
                                writer.write("  {\n");
                                writer.write("    \"prefix\": \"" + preifix + "\",\n");
                                writer.write("    \"prefix_len\": " + prefixLength + ",\n");
                                writer.write("    \"host_name\": \"Node"+node.getNodeId()+"\",\n");
                                writer.write("    \"device_name\": \"" + deviceIp.toString() + "\"\n");
                                writer.write("  },\n");
                            }
                        }

                    }
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static void fixJsonFiles(String dirPath) {
        Path routesDirPath = Paths.get(dirPath, "routes");
        Path resultsDirPath = Paths.get(dirPath, "results");
        try (Stream<Path> paths = Files.list(routesDirPath )) {
            paths.forEach(IO::processJsonFile);
        }catch (IOException e) {
            e.printStackTrace();
        }
        try (Stream<Path> paths = Files.list(resultsDirPath)) {
            paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(IO::processJsonFile);
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void processJsonFile(Path filePath) {
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);

            String trimmed = content.trim();
            if (trimmed.endsWith("]")||trimmed.endsWith("}")) {
                return;
            }
            // 删除最后一个字符
            if (bytes.length <= 2) {
                String modified = content + "\n]";
                Files.write(filePath, modified.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
            else {
                String modified = content.substring(0, content.length() - 2) + "\n]";
                Files.write(filePath, modified.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Failed to process file: " + filePath);
            e.printStackTrace();
        }
    }
    public static void writeCompressedRoutes(String filePath,Set<Subnet> batch, Context context) {
        try {
            Path routesDirPath = Paths.get(filePath, "routes");
            if (!Files.exists(routesDirPath)) {
                Files.createDirectories(routesDirPath);
            }
            for (Node node:context.getNodeMap().values()) {

                Path routesPath = routesDirPath.resolve("Node" + node.getNodeId() );
                boolean routesFileExists = Files.exists(routesPath);
                try (BufferedWriter writer = new BufferedWriter(
                            new FileWriter(routesPath.toFile(), true))) {
                    if (!routesFileExists) {
                        writer.write("[\n");
                        node.rib.getDirectRoutes().forEach((ip,map)->{
                            PhysicalNode pnode=node.getPhysicalNodeMap().get(ip);
                            IP deviceIp=context.getDeviceIP(ip);
                            map.forEach((subnet,routes)->{
                               routes.forEach(route->{
                                   IP nexthop=route.getNextHop();
                                   pnode.getPeerList().forEach((remoteIP,peer)->{
                                   if(peer.getLocalInterfaceIp().equals(nexthop)){
                                       IP remoteRouterId=context.getRouterId(remoteIP);
                                       int remoteNodeId=context.getNode(remoteRouterId).getNodeId();
                                       if (!context.getAllBgpPrefix().containsKey(route.getDestination())) return;
                                       try {
                                           writer.write("  {\n");
                                           writer.write("    \"action\": \"ANY\",\n");
                                           writer.write("    \"prefix\": \"" + route.getDestination().getBaseAddress().toString() + "\",\n");
                                           writer.write("    \"prefix_len\": " + route.getDestination().getPrefixLength() + ",\n");

                                           // nexthop_infs
                                           writer.write("    \"nexthop_infs\": [\n");
                                           writer.write("        \"" + "Node" + node.getNodeId() + "->" + "Node" + remoteNodeId + "\"\n");
                                           writer.write("    ],\n");
                                           writer.write("    \"devices\": [\n");
                                           writer.write("        \"" + deviceIp + "\"\n");
                                           writer.write("    ]\n");
                                           writer.write("  },\n");
                                       } catch (IOException e) {
                                           throw new RuntimeException(e);
                                       }
                                   }
                                   });
                               });
                            });
                        });
                    }
                    for (Subnet subnet:batch) {
                            Map<RouterIDs, List<Integer>> compressedResults = compressedRoutes(subnet, node, context);
                        for (Map.Entry<RouterIDs, List<Integer>> entry : compressedResults.entrySet()) {
                            RouterIDs routerIDs = entry.getKey();
                            List<Integer> remoteNodeIds = entry.getValue();
                            String preifix = subnet.getBaseAddress().toString();
                            int prefixLength = subnet.getPrefixLength();

                            writer.write("  {\n");
                            writer.write("    \"action\": \"ANY\",\n");
                            writer.write("    \"prefix\": \"" + preifix + "\",\n");
                            writer.write("    \"prefix_len\": " + prefixLength + ",\n");

                            // nexthop_infs
                            writer.write("    \"nexthop_infs\": [\n");
                            int j = 0;
                            for (Integer remoteNodeId : remoteNodeIds) {
                                j++;
                                if (j == remoteNodeIds.size()) {
                                    writer.write("        \"" + "Node" + node.getNodeId() + "->" + "Node" + remoteNodeId + "\"\n");
                                }
                                else {
                                    writer.write("        \"" + "Node" + node.getNodeId() + "->" + "Node" + remoteNodeId + "\",\n");
                                }
                            }

                            writer.write("    ],\n");

                            // device_name
                            writer.write("    \"devices\": [\n");
                            Set<IP> ids = routerIDs.getRouterIdList();
                            int i = 0;
                            for (IP ip : ids) {
                                i++;
                                if (i == ids.size()) {
                                    writer.write("        \"" + context.getDeviceIP(ip) + "\"\n");
                                } else {
                                    writer.write("        \"" + context.getDeviceIP(ip) + "\",\n");
                                }

                            }
                            writer.write("    ]\n");
                            writer.write("  },\n");
                        }
                    }
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }

    }


}
