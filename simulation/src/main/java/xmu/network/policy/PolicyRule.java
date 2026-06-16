package xmu.network.policy;




import xmu.network.Context;
import xmu.network.routes.BGPRoute;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PolicyRule {
    private int id;
    private boolean rule ;
    Map<Byte, Set<MatchCondition>> matchConditions= new HashMap<Byte, Set<MatchCondition>>();
    ApplyAction apply;

    public PolicyRule(int id,boolean rule,Map<Byte,Set<MatchCondition>> matchConditions,ApplyAction apply) {
        this.id = id;
        this.rule = rule;
        this.matchConditions = matchConditions;
        this.apply = apply;
    }

    public boolean ifMatch(BGPRoute route) {
        boolean result = true;
        if (matchConditions.isEmpty()) return true;
        for (Map.Entry<Byte, Set<MatchCondition>> filter : matchConditions.entrySet()) {
            Byte key = filter.getKey();
            if (key == Context.IP_PREFIX_FILTER) {
                boolean ip_prefix = false;
                for (MatchCondition matchCondition : filter.getValue()) {
                    if (matchCondition.ipPrefixFilter(route)) {
                        ip_prefix = true;
                        break;
                    }
                }
                result=result && ip_prefix;
            }
            if (key == Context.AS_PATH_FILTER) {
                boolean as_prefix = false;
                for (MatchCondition matchCondition : filter.getValue()) {

                    if (matchCondition.asPathFilter(route)) {
                        as_prefix = true;
                        break;
                    }
                }
                result=result && as_prefix;
            }
            if (key == Context.IP_COMMUNITY_FILTER) {
                int count = 0;
                for (MatchCondition matchCondition : filter.getValue()) {

                    if (matchCondition.communityFilter(route)) {
                        count++;
                    }
                }
                result=result && count == filter.getValue().size();
            }
        }
        return result;
    }
    public boolean getRule() {
        return rule;
    }

    public ApplyAction getApply() {
        return apply;
    }
}
