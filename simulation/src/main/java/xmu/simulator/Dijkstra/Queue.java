package xmu.simulator.Dijkstra;

import xmu.network.others.Pair;
import xmu.network.routes.BGPRoute;

import java.util.*;

public class Queue {
    private static final Comparator<Pair<BGPRoute, Integer>> ROUTE_COMPARATOR = Comparator
            .comparing(Pair::getKey,
                    Comparator.comparingInt((BGPRoute r) -> -r.getPreferredValue())
                            .thenComparingInt(r -> -r.getLocPrf())
                            .thenComparingInt(r -> -r.getMethod())
                            .thenComparingInt(r -> r.getAsPath().size())
                            .thenComparingInt(r -> -r.getOrigin())
                            .thenComparingInt(BGPRoute::getMed)
                            .thenComparingLong(r ->r.getRouterIdList().getMinRouterId().ipToLong()));

    // 用优先队列替代每次取元素前全量排序，降低大批量前缀计算时的调度成本。
    private final java.util.Queue<Pair<BGPRoute,Integer>> queue = new PriorityQueue<>(ROUTE_COMPARATOR);
    private final Set<Pair<BGPRoute,Integer>> activeEntries = new HashSet<>();

    public synchronized Pair<BGPRoute,Integer> getFirst() {
        while (!queue.isEmpty()) {
            Pair<BGPRoute,Integer> first = queue.poll();
            if (activeEntries.remove(first)) {
                return first;
            }
        }
        throw new NoSuchElementException("queue is empty");
    }
    public synchronized void add(BGPRoute route,Integer node) {
        if(route!=null&&node!=null) {
            Pair<BGPRoute, Integer> entry = new Pair<>(route,node);
            if (activeEntries.add(entry)) {
                queue.add(entry);
            }
        }
    }
    public synchronized boolean contains(BGPRoute route,Integer node) {
        return activeEntries.contains(new Pair<>(route,node));
    }
    public synchronized void addAll(List<BGPRoute> routes,Integer node) {
        for(BGPRoute route:routes) {
            add(route, node);
        }
    }
    public synchronized  void remove(BGPRoute route, Integer node) {
        activeEntries.remove(new Pair<>(route,node));
    }
    public boolean isEmpty() {
        return activeEntries.isEmpty();
    }
}
