package xmu.network.routes;

import xmu.network.Context;
import xmu.network.others.IP;
import xmu.network.others.Subnet;
import xmu.network.policy.Policy;
import xmu.network.topo.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RouteMap {

    //TODO: 为每个对象引入直链路由、静态路由和BGP路由

    public static Route directRoute(Interface iface,IP routerId,Context context){
       Subnet subnet=iface.getSegment().formalNetworkAddress();
       IP nextHop=iface.getPeerIp();
       int ifaceNameId=iface.getInterfaceNameId();
       if(context.getInterfaceName(ifaceNameId).contains("Loopback"))
       {
           nextHop=iface.getPeerIp();
       }
       RouterIDs routerIdList=new RouterIDs();
       routerIdList.add(routerId);
        return  new Route(Context.PRE_DIRECT_DEFAULT,Context.COST_DEFAULT,subnet,nextHop,ifaceNameId,Context.PRO_DIRECT,routerIdList);
    }

    public static Route staticRoute(Interface iface,IP routerId){
        Subnet subnet=iface.getSegment().formalNetworkAddress();
        IP nextHop=iface.getNextHop();
        int ifaceNameId=iface.getInterfaceNameId();
        RouterIDs routerIdList=new RouterIDs();
        routerIdList.add(routerId);
        return  new Route(Context.PRE_STATIC_DEFAULT,Context.COST_DEFAULT,subnet,nextHop,ifaceNameId,Context.PRO_STATIC,routerIdList);
    }

    public static List<BGPRoute> importRoute(List<Route> routes,int nodeId, Policy policy,Context context){
        List<BGPRoute> bgpRoutes=new ArrayList<BGPRoute>();
        for(Route route:routes){
            BGPRoute bgpRoute=new BGPRoute(route);
            if(policy.routePolicy(bgpRoute)){
                bgpRoute.setMethod(Context.MET_IMP);
                bgpRoute.setOrigin(Context.ORI_INCOM);
                bgpRoute.setProtocol(Context.PRO_BGP);
                bgpRoute.setAdvertise(true);
                bgpRoutes.add(bgpRoute);
                context.registerBgpPrefix(bgpRoute.getNetwork(),nodeId);
            }
        }
        return bgpRoutes;
    }

    public static List<BGPRoute> networkRoutes(List<Route> routes,int nodeId, Policy policy,Subnet subnet,Context context){
        List<BGPRoute> bgpRoutes=new ArrayList<BGPRoute>();
        for(Route route:routes){
            Subnet dstSubnet=route.getDestination();
//            byte prefixLength=dstSubnet.getPrefixLength();
            if(subnet.equals(dstSubnet)) {
                BGPRoute bgpRoute = new BGPRoute(route);
                if (policy.routePolicy(bgpRoute)) {
                    bgpRoute.setMethod(Context.MET_NET);
                    bgpRoute.setOrigin(Context.ORI_IGP);
                    bgpRoute.setProtocol(Context.PRO_BGP);
                    bgpRoutes.add(bgpRoute);
                    bgpRoute.setAdvertise(true);
                    context.registerBgpPrefix(dstSubnet,nodeId);
                }
            }
        }
        return bgpRoutes;
    }

    public static void routeMap(Context context){
        Map<Integer,Node>nodeMap=context.getNodeMap();
        for(Node node:nodeMap.values()){

            int  nodeId=node.getNodeId();
            for (PhysicalNode p:node.getPhysicalNodeMap().values()) {
                List<Route> staticRouteList=new ArrayList<>();
                List<Route> directRouteList=new ArrayList<>();
                //直链路由，静态路由
                for (Interface iface: p.getInterfaceList()){
                    if (iface.getProtocol()==Context.PRO_STATIC){
                        staticRouteList.add(staticRoute(iface,p.getRouterId()));
                    }
                    else if (iface.getProtocol()==Context.PRO_DIRECT){
                        directRouteList.add(directRoute(iface,p.getRouterId(),context));
                    }
                    else {
                        throw new RuntimeException("Unknown protocol");
                    }
                }
                //BGP路由
                List<BGPRoute> bgpRouteList=new ArrayList<>();
                for(ImportRoute importPolicy:p.getImportPolicyList()){
                    Policy policy=context.getPolicy(importPolicy.getImportPolicyId());
                    byte protocol=importPolicy.getProtocol();
                    if(protocol==Context.PRO_DIRECT){
                        bgpRouteList.addAll(importRoute(directRouteList,nodeId,policy,context));
                    }
                    else if(protocol==Context.PRO_STATIC){
                        bgpRouteList.addAll(importRoute(staticRouteList,nodeId,policy,context));
                    }
                    else {
                        throw new RuntimeException("Unknown protocol");
                    }
                }
                for (NetworkRoute networkPolicy:p.getNetworkPolicyList()){
                    Policy policy=context.getPolicy(networkPolicy.getNetworkPolicyId());
                    Subnet segment=networkPolicy.getSegment();
                    bgpRouteList.addAll(networkRoutes(directRouteList,nodeId,policy,segment,context));
                    bgpRouteList.addAll(networkRoutes(staticRouteList,nodeId,policy,segment,context));
                }


                //添加到rib中
                node.rib.addRoutes(staticRouteList);
                node.rib.addRoutes(directRouteList);
                node.rib.addBgpRoutes(bgpRouteList);

            }
            //聚合路由
            List<BGPRoute> aggregationRouteList=new ArrayList<>();
            for(AggregateRoute aggregateRoute:node.getAggregateRouteList()){
                Policy attributePolicy=context.getPolicy(aggregateRoute.getAttributePolicyId());
                Subnet segment=aggregateRoute.getSegment();
                BGPRoute route=new BGPRoute();
                route.setMethod(Context.MET_AGG);
                route.setNetwork(segment);
                route.setProtocol(node.getPreferenceLocal());
                route.setNextHop(IP.LOOPBACK);
                route.setOrigin(Context.ORI_INCOM);
                route.setInterfaceId(Context.NULL0_INTERFACE_ID);
                if(attributePolicy.routePolicy(route)){
                    aggregationRouteList.add(route);
                    context.registerAggPrefix(segment,nodeId);
                }
            }
            node.rib.addAggRoutes(aggregationRouteList);
        }
    }
}
