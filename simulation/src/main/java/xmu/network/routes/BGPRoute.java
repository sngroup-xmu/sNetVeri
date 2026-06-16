package xmu.network.routes;

import java.util.*;

import xmu.network.others.AS;
import xmu.network.others.IP;
import xmu.network.others.Subnet;

public class BGPRoute {
    //协议间
    private int protocol; // BGP(聚合), IBGP, EBGP

    //协议内
    private int preferredValue;   //华为特有属性，只在本地有效
    private int locPrf;  // 本地preference，只在as内有效
    private byte method;//手动聚合路由、自动聚合路由、network命令引入的路由、import-route命令引入的路由、从对等体学习的路由
    private List<AS> asPath=new ArrayList<>();  //as path
    private Byte origin;//IGP、EGP、Incomplete的路由
    private int med;     //它用于判断流量进入AS时的最佳路由，它用于判断流量进入AS时的最佳路由



    private Set<Byte> statusCode=new HashSet<>(); //状态码
    private Subnet network;  //子网
    private IP nextHop;      //下一跳
    private Set<Integer> communityValue=new HashSet<>();  //有的community

    private boolean advertise; //是否宣告

//    private boolean advertiseEBGP;
//    private boolean advertiseSubAs;
//    private boolean advertiseIBGP;

//    private IP minRouterId;
//    private IP minPeerIp;
//    private AS minAs;

    private RouterIDs routerIdList=new RouterIDs();
    private RouterIDs remoteRouterIdList=new RouterIDs();
    private int interfaceId;

//
//    private boolean drop;
//    private int remoteNodeId;
//    private IP deviceIp;
    public BGPRoute(Route route){
        this.network=route.getDestination();
        this.nextHop=route.getNextHop();
        this.interfaceId=route.getInterfaceId();
        this.routerIdList=route.getRouterIdList();
    }
    public BGPRoute(BGPRoute route){
       //TODO：深拷贝
        this.network=route.getNetwork();
        this.nextHop=route.getNextHop();
        this.protocol=route.getProtocol();
        this.preferredValue=route.getPreferredValue();
        this.locPrf=route.getLocPrf();
        this.method=route.getMethod();
        this.origin=route.getOrigin();
        this.med=route.getMed();
        this.communityValue=new HashSet<>(route.getCommunityValue());
        this.asPath=new ArrayList<>(route.getAsPath());
        this.routerIdList=new RouterIDs(route.getRouterIdList());
        this.remoteRouterIdList=new RouterIDs(route.getRemoteRouterIdList());
        this.advertise=route.isAdvertise();
        this.interfaceId=route.getInterfaceId();
    }
    public BGPRoute(){}
    public String asPathPrint(String split){
        StringBuilder sb = new StringBuilder();
        // 从后向前遍历 List
        for (int i = asPath.size() - 1; i >= 0; i--) {
            AS as = asPath.get(i);
            sb.append(as.toString()).append(split);
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - split.length());
        }
        return sb.toString();
    }

    public Subnet getNetwork() {
        return network;
    }
    public Set<Integer> getCommunityValue() {
        return communityValue;
    }
    public List<AS> getAsPath() {
        return asPath;
    }

    public Byte getOrigin() {
        return origin;
    }
    public int getMed() {
        return med;
    }

    public int getLocPrf() {
        return locPrf;
    }

    public Byte getMethod() {
        return method;
    }

    public int getPreferredValue() {
        return preferredValue;
    }

    public int getProtocol() {
        return protocol;
    }
    public RouterIDs getRouterIdList() {
        return routerIdList;
    }
    public RouterIDs getRemoteRouterIdList() {
        return remoteRouterIdList;
    }

    public IP getNextHop() {
        return nextHop;
    }

    public boolean isAdvertise() {
        return advertise;
    }

    public void setNetwork(Subnet network) {
        this.network = network;
    }
    public void setNextHop(IP nextHop) {
        this.nextHop = nextHop;
    }
    public void setInterfaceId(int interfaceId) {
        this.interfaceId = interfaceId;
    }
    public void setLocPrf(int locPrf) {
        this.locPrf = locPrf;
    }

    public void setMed(int med) {
        this.med = med;
    }

    public void setStatusCode(Set<Byte> statusCode) {
        this.statusCode = statusCode;
    }

    public Set<Byte> getStatusCode() {
        return statusCode;
    }

    public void setPreferredValue(int preferredValue) {
        this.preferredValue = preferredValue;
    }
    public void setMethod(byte method) {
        this.method = method;
    }
    public void setOrigin(Byte origin) {
        this.origin = origin;
    }

    public void setAsPath(List<AS> asPath) {
        this.asPath = asPath;
    }

    public void setRouterIdList(RouterIDs routerIdList) {
        this.routerIdList = routerIdList;
    }
    public void setRemoteRouterIdList(RouterIDs remoteRouterIdList) {
        this.remoteRouterIdList = remoteRouterIdList;
    }
    public void setAdvertise(boolean advertise) {
        this.advertise = advertise;
    }
    public void setProtocol(int protocol) {
        this.protocol = protocol;
    }

    public int getInterfaceId() {
        return interfaceId;
    }
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BGPRoute bgpRoute = (BGPRoute) obj;
        return Objects.equals(this.network, bgpRoute.network) &&
                Objects.equals(this.communityValue, bgpRoute.communityValue)&&
                Objects.equals(this.med, bgpRoute.med)&&
                Objects.equals(this.locPrf, bgpRoute.locPrf)&&
                Objects.equals(this.preferredValue, bgpRoute.preferredValue)&&
                Objects.equals(this.asPath, bgpRoute.asPath)&&
                Objects.equals(this.origin, bgpRoute.origin)&&
                Objects.equals(this.statusCode, bgpRoute.statusCode)&&
                Objects.equals(this.method, bgpRoute.method)&&
                Objects.equals(this.protocol, bgpRoute.protocol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.network,
                this.communityValue,
                this.med,
                this.locPrf,
                this.preferredValue,
                this.asPath,
                this.origin,
                this.statusCode,
                this.method,
                this.protocol
        );
    }
}
