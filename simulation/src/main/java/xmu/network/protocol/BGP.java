package xmu.network.protocol;


import xmu.network.Context;
import xmu.network.others.AS;
import xmu.network.others.IP;
import xmu.network.others.Subnet;
import xmu.network.policy.Policy;
import xmu.network.routes.BGPRoute;
import xmu.network.routes.RouterIDs;
import xmu.network.topo.AggregateRoute;
import xmu.network.topo.Edge;
import xmu.network.topo.Node;
import xmu.network.topo.PhysicalNode;

import java.util.*;

public class BGP {
    //TODO：路由选路规则，聚合路由规则
    //路由选路 压缩
    public static final Comparator  ROUTE_COMPARATOR = Comparator
                .comparingInt((BGPRoute r) -> -r.getPreferredValue())               // 较大 preferredValue 优先
                .thenComparingInt(r -> -r.getLocPrf())                             // 较大 LocPrf 优先
                .thenComparingInt(r -> -r.getMethod())                             // 较大 Method 优先
                .thenComparingInt(r -> r.getAsPath().size())                       // AS Path 越短越优
                .thenComparingInt(r -> -r.getOrigin())                             // Origin 值大的优先（如IGP = 2）
                .thenComparingInt(BGPRoute::getMed)                                // MED 越小越优
                .thenComparingLong(r -> r.getRemoteRouterIdList().getMinRouterId().ipToLong());      // RouterId 小优先
    //ECMP选路规则
    public static final Comparator  ECMP_COMPARATOR = Comparator
                    .comparingInt((BGPRoute r) -> -r.getPreferredValue())               // 较大 preferredValue 优先
                    .thenComparingInt(r -> -r.getLocPrf())                             // 较大 LocPrf 优先
                    .thenComparingInt(r -> -r.getMethod())                             // 较大 Method 优先
                    .thenComparingInt(r -> r.getAsPath().size())                       // AS Path 越短越优
                    .thenComparingInt(r -> -r.getOrigin())                             // Origin 值大的优先（如IGP = 2）
                    .thenComparingInt(BGPRoute::getMed);                                // MED 越小越优      // RouterId 小优先

    public static Map<IP,List<BGPRoute>> findECMPRoutes(Subnet subnet, Node node) {
        int maxLbNum=node.getMaxLBNum();
        Map<RouterIDs, List<BGPRoute>> bgpRouteMap =
                node.rib.getBgpRoutes().get(subnet);
        if (bgpRouteMap == null || bgpRouteMap.isEmpty()) {
            return Collections.emptyMap();
        }
        // 1收集所有 route
        List<BGPRoute> allRoutes = new ArrayList<>();
        for (List<BGPRoute> list : bgpRouteMap.values()) {
            if (list != null) {
                allRoutes.addAll(list);
            }
        }
        // 2建立 RouterID → routes 映射
        Map<IP, List<BGPRoute>> routerIdMap = new HashMap<>();
        for (BGPRoute route : allRoutes) {
            for (IP routerId : route.getRouterIdList().getRouterIdList()) {
                routerIdMap
                        .computeIfAbsent(routerId, k -> new ArrayList<>())
                        .add(route);
            }
        }
        Map<IP,List<BGPRoute>> resultMap = new HashMap<>();
        List<BGPRoute> resultList = new ArrayList<>();
        // 3对每个 RouterID 独立选路
        for (Map.Entry<IP, List<BGPRoute>> entry : routerIdMap.entrySet()) {
            List<BGPRoute> routes = entry.getValue();
            if (routes.isEmpty()) continue;
            // 3.1 找该 RouterID 的 best
            BGPRoute best = Collections.min(routes, ECMP_COMPARATOR);
            // 3.2 找 ECMP 等价组
            List<BGPRoute> ecmpGroup = new ArrayList<>();
            for (BGPRoute r : routes) {
                if (ECMP_COMPARATOR.compare(r, best) == 0) {
                    ecmpGroup.add(r);
                }
            }
            // 3.3 排序（稳定选择）
            ecmpGroup.sort(ROUTE_COMPARATOR);
            // 3.4 取最多 maxLbNum 条
            int count = Math.min(maxLbNum, ecmpGroup.size());
            for (int i = 0; i < count; i++) {
                resultList.add(new BGPRoute(ecmpGroup.get(i)));
            }
            resultMap.put(entry.getKey(), resultList);
        }
        return resultMap;

    }
    public static List<BGPRoute> findBestRoute(Subnet subnet, Node node,Context context) {
        Map<RouterIDs, List<BGPRoute>> bgpRouteMap = node.rib.getBgpRoutes().get(subnet);

        if (bgpRouteMap == null || bgpRouteMap.isEmpty()) return null;

        Set<IP> routerIdSet=new HashSet<>();
        List<BGPRoute> routes = new ArrayList<>();
        //先在每个RouterIDs里面选一个
        for (Map.Entry<RouterIDs, List<BGPRoute>> bgpIpRoutes : bgpRouteMap.entrySet()) {
            List<BGPRoute> routeList = bgpIpRoutes.getValue();
            routeList.sort(ROUTE_COMPARATOR);
            BGPRoute bgpRoute = routeList.get(0);
            routes.add(bgpRoute);
        }
        //再重新排序，为每个RouterID都选一个
        routes.sort(ROUTE_COMPARATOR);
        Map<IP,PhysicalNode> physicalNodeMap=node.getPhysicalNodeMap();
        List<BGPRoute> bestRoutes=new ArrayList<>();
        for (BGPRoute route : routes) {
            BGPRoute result=new BGPRoute(route);
            RouterIDs routerIds = route.getRemoteRouterIdList();
            if(routerIdSet.isEmpty()){
                routerIdSet.addAll(routerIds.getRouterIdList());
                changeAsPath(result,context);
                bestRoutes.add(result);
            }
            else {
                routerIds.getRouterIdList().forEach(routerId -> {
                    if(routerIdSet.contains(routerId)){
                        result.getRouterIdList().remove(routerId);
                    }
                });
                if(!result.getRouterIdList().isEmpty()){
                    routerIdSet.addAll(result.getRouterIdList().getRouterIdList());
                    changeAsPath(result,context);
                    bestRoutes.add(result);
                }
            }
        }
        return bestRoutes;
    }

    public static void changeAsPath(BGPRoute route, Context cxt) {
        IP miniRouterId=route.getRemoteRouterIdList().getMinRouterId();
        if(miniRouterId.equals(IP.MAX)){return ;}
        AS logicalASnum=cxt.getNode(miniRouterId).getAsNumber();
        if(!logicalASnum.isLogic()) return ;
        AS physicalASmum=cxt.getAS(miniRouterId);
        List<AS> asPath=route.getAsPath();
        List<AS> newAsPath=new ArrayList<>();
        for (AS as : asPath) {
            if(as.equals(logicalASnum)){
                newAsPath.add(physicalASmum);
            }
            else {
                newAsPath.add(as);
            }
        }
        route.setAsPath(newAsPath);
    }
    //聚合路由筛选
    public static void aggregate(Node node, BGPRoute route, Context context) {
        List<AggregateRoute> aggPolicyList=node.getAggregateRouteList();
        Map<Subnet,BGPRoute> aggregationRoutes=node.rib.getAggregationBGPRoutes();
        if(aggPolicyList.isEmpty()){
            return;
        }
        for (AggregateRoute aggPolicy : aggPolicyList) {
            Subnet segment = aggPolicy.getSegment();

            if(segment.contains(route.getNetwork(),segment.getPrefixLength(),Subnet.LENGTH_MAX)) {
                BGPRoute aggRoute = aggregationRoutes.get(segment);
                if (aggRoute.getOrigin() < route.getOrigin()) {
                    aggRoute.setOrigin(route.getOrigin());
                }
                aggRoute.setAdvertise(true);
                aggRoute.getRouterIdList().addAll(route.getRouterIdList());

                if (aggPolicy.isDetailSuppress()) {
                   Policy policy= context.getPolicy(aggPolicy.getSuppressPolicyId());
                   if(policy.routePolicy(aggRoute)){
                       route.setAdvertise(false);
                   }
                }
            }
        }
    }

    public static boolean asFilter(BGPRoute route, Node localNode, Node remoteNode)  {

        List<AS> asList=route.getAsPath();
        RouterIDs newRouterIdList=new RouterIDs();
        RouterIDs localRouterIdList=route.getRouterIdList();
        //修改routerids
        route.setRemoteRouterIdList(new RouterIDs(localRouterIdList));
        route.getRouterIdList().clear();
        List<AS> localAsList=new ArrayList<>();
        // aslist中有的逻辑as拆成真实as
        Map<IP,PhysicalNode> localPhysicalNodeList=localNode.getPhysicalNodeMap();
        for (IP routerId: localRouterIdList.getRouterIdList()){
            localAsList.add(localPhysicalNodeList.get(routerId).getAsNum());
        }
        for(PhysicalNode pnode:remoteNode.getPhysicalNodeMap().values()){
            if(asList.contains(pnode.getAsNum())||localAsList.contains(pnode.getAsNum())){
                continue;
            }
            else {
                newRouterIdList.add(pnode.getRouterId());
            }
        }
        if(newRouterIdList.getRouterIdList().isEmpty()){
            return false;
        }
        else
        {
            route.setRouterIdList(newRouterIdList);
            return true;
        }
    }
    public static BGPRoute trans(Node localNode, Node remoteNode,
                                 BGPRoute route, Edge edge,Context context){

        if (route.getMethod() == Context.MET_IMP && route.getNetwork().getPrefixLength() == 32) return null;

        if(route.getProtocol()!=localNode.getPreferenceExternal()&&route.getProtocol()!=localNode.getPreferenceLocal()){
            aggregate(localNode, route, context);
        }

        if (!route.isAdvertise()) return null;

        BGPRoute transRoute = new BGPRoute(route);
        Policy exPolicy = context.getPolicy(edge.getExportPolicyId());
        Policy imPolicy = context.getPolicy(edge.getRemoteImportPolicyId());

        if (exPolicy.routePolicy(transRoute)) {
            RouterIDs localRouterIds = transRoute.getRouterIdList();
            Set<IP> routerIdList = localRouterIds.getRouterIdList();
            if (routerIdList.size() > 1) { //加逻辑as
                transRoute.getAsPath().add(localNode.getAsNumber());

            } else {//加真实的as
                IP routerId = routerIdList.iterator().next();
                PhysicalNode physicalNode = localNode.getPhysicalNodeMap().get(routerId);
                if(physicalNode==null){
                    System.out.println("physicalNode is null");
                }
                if(transRoute.getAsPath()==null){
                    System.out.println("transRoute.getAsPath()==null");
                }
                transRoute.getAsPath().add(physicalNode.getAsNum());

            }
            transRoute.setLocPrf(Context.LP_DEFAULT);
            if (asFilter(transRoute, localNode, remoteNode)) {
                if (imPolicy.routePolicy(transRoute)) {
                    transRoute.setProtocol(remoteNode.getPreferenceExternal());
                    remoteNode.rib.addBgpRoute(transRoute);
                    aggregate(remoteNode, transRoute, context);
                    return new BGPRoute(transRoute);
                }
            }

        }
        return null;
    }

    public static void withdrawSet(List<BGPRoute> oldBestSet,
                                   List<BGPRoute> newBestSet,List<BGPRoute> withdrawRoutes,
                                   List<BGPRoute> updateRoutes) {
        if(oldBestSet==null&&newBestSet==null){return;}
        if(oldBestSet==null){
            updateRoutes.addAll(newBestSet);
            return;
        }
        if(newBestSet==null){
            withdrawRoutes.addAll(oldBestSet);
            return;
        }

        for (BGPRoute newRoute : newBestSet) {
            if (!oldBestSet.contains(newRoute)) {
                updateRoutes.add(newRoute);
            }
        }
        for (BGPRoute oldRoute : oldBestSet) {
            if (!newBestSet.contains(oldRoute)) {
                withdrawRoutes.add(oldRoute);
            }
        }
    }
}
