package xmu.network.routes;


import xmu.network.Context;
import xmu.network.others.IP;
import xmu.network.others.Subnet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RIB {
    private Map<IP, Map<Subnet,List<Route>>> staticRoutes=new ConcurrentHashMap<>();  //静态路由
    private Map<IP, Map<Subnet,List<Route>>> directRoutes=new ConcurrentHashMap<>();  //直连路由
    private Map<Subnet,Map<RouterIDs,List<BGPRoute>>> bgpRoutes = new ConcurrentHashMap<>();  //BGP路由表
    private Map<Subnet,BGPRoute> aggregationBGPRoutes = new ConcurrentHashMap<>();   //聚合路由记录



    public void addRoutes(List<Route> routesList) {
        routesList.forEach(route -> {
            if(route.getProtocol()== Context.PRO_STATIC){
                staticRoutes.computeIfAbsent(route.getRouterIdList().getMinRouterId(),k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(route.getDestination(), k -> Collections.synchronizedList(new ArrayList<>())).add(route);
            }
            else {
                directRoutes.computeIfAbsent(route.getRouterIdList().getMinRouterId(),k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(route.getDestination(), k -> Collections.synchronizedList(new ArrayList<>())).add(route);
            }
        });
    }
    public void addBgpRoutes( List<BGPRoute> routesList) {
        routesList.forEach(bgpRoute -> {
            bgpRoutes
                    .computeIfAbsent(bgpRoute.getNetwork(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(bgpRoute.getRouterIdList(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(bgpRoute);
        });
    }

    public void addAggRoutes( List<BGPRoute> routesList) {
        routesList.forEach(bgpRoute -> {
            aggregationBGPRoutes.put(bgpRoute.getNetwork(), bgpRoute);
        });
    }

    public  void  addBgpRoute( BGPRoute route) {
        bgpRoutes.computeIfAbsent(route.getNetwork(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(route.getRouterIdList(), k -> Collections.synchronizedList(new ArrayList<>())).add(route);
    }
    public void removeBgpRoute( BGPRoute route) {
        bgpRoutes.get(route.getNetwork()).remove(route);
    }
    public Map<IP, Map<Subnet,List<Route>>> getStaticRoutes() {
        return staticRoutes;
    }
    public Map<IP, Map<Subnet,List<Route>>> getDirectRoutes() {
        return directRoutes;
    }

    public Map<Subnet, BGPRoute> getAggregationBGPRoutes() {
        return aggregationBGPRoutes;
    }

    public Map<Subnet, Map<RouterIDs, List<BGPRoute>>> getBgpRoutes() {
        return bgpRoutes;
    }
    //TODO：选择路由到routes
}
