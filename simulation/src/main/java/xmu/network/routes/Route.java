package xmu.network.routes;


import xmu.network.Context;
import xmu.network.others.IP;
import xmu.network.others.Subnet;

import java.util.HashSet;
import java.util.Set;

public class Route implements Comparable<Route> {

    private int preference; //direct>static>bgp
    private int cost;
    private Subnet destination;

    private IP nextHop;      // 下一跳
    private int interfaceId;          // 接口id

    private int protocol;     // 路由协议（如 BGP、静态等）
    private Set<Byte> flags=new HashSet<>();
    private RouterIDs routerIdList=new RouterIDs();

    public  Route(int preference, int cost, Subnet destination, IP nextHop, int interfaceId, int protocol,RouterIDs routerIdList) {
        this.preference = preference;
        this.cost = cost;
        this.destination = destination;
        this.nextHop = nextHop;
        this.interfaceId = interfaceId;
        this.protocol = protocol;
        this.routerIdList=routerIdList;

    }

    public Route(BGPRoute route) {
        this.preference=route.getProtocol();
        this.cost= Context.COST_DEFAULT;
        this.destination=route.getNetwork();
        this.nextHop=route.getNextHop();
        this.interfaceId=route.getInterfaceId();
        this.routerIdList=route.getRouterIdList();
    }
    public Subnet getDestination() {
        return destination;
    }
    public IP getNextHop() {
        return nextHop;
    }
    public int getInterfaceId(){
        return interfaceId;
    }
    public RouterIDs getRouterIdList(){
        return routerIdList;
    }

    public int getProtocol() {
        return protocol;
    }

    public Set<Byte> getFlags() {
        return flags;
    }

    public void setProtocol(int protocol) {
        this.protocol = protocol;
    }

    public void setPreference(int preference) {
        this.preference = preference;
    }

    public int getPreference() {
        return preference;
    }

    public void setNextHop(IP nextHop) {
        this.nextHop = nextHop;
    }
    @Override
    public int compareTo(Route o) {
        return this.destination.compareTo(o.destination);
    }
}
