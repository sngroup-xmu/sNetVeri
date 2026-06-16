package xmu.network.routes;

import xmu.network.others.IP;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class RouterIDs {
    private Set<IP> routerIdList=new HashSet<>();
    private IP minRoueterId;

    public RouterIDs() {
        minRoueterId=IP.MAX;
    }
    public RouterIDs(Set<IP> routerIdList) {
        minRoueterId=IP.MAX;
        this.routerIdList=routerIdList;
        for (IP ip : routerIdList) {
            if(minRoueterId.ipToLong()>ip.ipToLong()){
                minRoueterId=ip;
            }
        }
    }
    public RouterIDs(RouterIDs routerIDs) {
        this.routerIdList=new HashSet<>(routerIDs.routerIdList);
        this.minRoueterId=new IP(routerIDs.minRoueterId);
    }
    public void add(IP routerId) {
        if (minRoueterId.ipToLong()>routerId.ipToLong()) {
            minRoueterId=routerId;
        }
        routerIdList.add(routerId);
    }
    public void addAll(RouterIDs routerIdList) {
        if(minRoueterId.ipToLong()>routerIdList.getMinRouterId().ipToLong()) {
            minRoueterId=routerIdList.getMinRouterId();
        }
        this.routerIdList.addAll(routerIdList.routerIdList);
    }
    public void remove(IP routerId) {
        routerIdList.remove(routerId);
        if(minRoueterId.ipToLong()==routerId.ipToLong()){
            minRoueterId=IP.MAX;
            for (IP id : routerIdList) {
                if (minRoueterId.ipToLong()>id.ipToLong()) {
                    minRoueterId=id;
                }
            }
        }
    }
    public boolean isEmpty() {
        return routerIdList.isEmpty();
    }
    public boolean contains(IP routerId) {
        return routerIdList.contains(routerId);
    }
    public void clear() {
        routerIdList.clear();
        minRoueterId=IP.MAX;
    }
    public IP getMinRouterId() {
       return minRoueterId;
    }
    public Set<IP> getRouterIdList() {
        return routerIdList;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RouterIDs)) return false;
        RouterIDs that = (RouterIDs) o;
        return Objects.equals(routerIdList, that.routerIdList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routerIdList);
    }
}
