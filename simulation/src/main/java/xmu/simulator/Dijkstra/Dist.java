package xmu.simulator.Dijkstra;

import xmu.network.routes.BGPRoute;
import xmu.network.routes.RouterIDs;

import java.util.ArrayList;
import java.util.List;

public class Dist {
    private List<BGPRoute> bestRoutes=new ArrayList<>();
    private RouterIDs routerIdSet=new RouterIDs();
    private void rebuildRouterIdSet() {
        // 中文注释：routerIdSet 是快速索引，增删路由后必须和 bestRoutes 保持一致。
        routerIdSet.clear();
        for (BGPRoute bestRoute : bestRoutes) {
            routerIdSet.addAll(bestRoute.getRouterIdList());
        }
    }
    public Dist(BGPRoute route) {
        this.bestRoutes.add(route);
        this.routerIdSet.addAll(route.getRouterIdList());
    }
    public Dist(List<BGPRoute> routes) {
        if(routes!=null){
            this.bestRoutes=new ArrayList<>(routes);
            rebuildRouterIdSet();
        }
    }
    public boolean contains(BGPRoute route) {
        if(routerIdSet.getRouterIdList().containsAll(route.getRouterIdList().getRouterIdList())){
            return true;
        }
        return false;
    }
    public synchronized void add(BGPRoute route) {
        bestRoutes.add(route);
        routerIdSet.addAll(route.getRouterIdList());
    }
    public synchronized void addAll(List<BGPRoute> routes) {
        bestRoutes.addAll(routes);
        for (BGPRoute route : routes) {
            routerIdSet.addAll(route.getRouterIdList());
        }
    }
    public List<BGPRoute> getBestRoutes() {
        return bestRoutes;
    }
    public void remove(BGPRoute route) {
        bestRoutes.remove(route);
        rebuildRouterIdSet();
    }
}
