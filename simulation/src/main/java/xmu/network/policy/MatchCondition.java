package xmu.network.policy;




import xmu.network.others.Subnet;
import xmu.network.routes.BGPRoute;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MatchCondition {

    private byte type;
    private Set<Integer> commValue=new HashSet<Integer>();
    private String  asPathFilter;
    private Subnet prefixFilter;
    private byte greater=0;
    private byte less=0;
    private boolean matchMode;

    public MatchCondition(byte type, Set<Integer> commValue, String asPathFilter, Subnet prefixFilter,byte greater,byte less, boolean matchMode) {
        this.type = type;
        this.commValue = commValue;
        this.asPathFilter = asPathFilter;
        this.prefixFilter = prefixFilter;
        this.greater=greater;
        this.matchMode=matchMode;
        this.less=less;
    }
    public byte getType() {
        return type;
    }

    public boolean ipPrefixFilter(BGPRoute route)  {
        Subnet routePrefix= route.getNetwork();
        return prefixFilter.contains(routePrefix,greater,less);
    }
    public boolean communityFilter(BGPRoute route){
        return route.getCommunityValue().containsAll(commValue);
    }
    public boolean asPathFilter(BGPRoute route){
        if(asPathFilter.equals("^$")){
            return route.getAsPath().isEmpty();
        }else {
            Pattern pattern = Pattern.compile(asPathFilter);
            Matcher matcher = pattern.matcher(route.asPathPrint("_"));
            return matcher.find();
        }
    }
}
