package xmu.network.policy;






import xmu.network.routes.BGPRoute;

import java.util.ArrayList;
import java.util.List;

public class Policy {
    int id;
    List<PolicyRule> rules=new ArrayList<PolicyRule>();

    public Policy(int id,List<PolicyRule> rules) {
        this.id = id;
        this.rules = rules;
    }
    public int getId() {
        return id;
    }

    public boolean routePolicy(BGPRoute route){
        if(rules.isEmpty()){
            return true;
        }
        for(PolicyRule rule:rules){
            if(rule.ifMatch(route)){
                if(rule.getRule()){
                    rule.getApply().applyPolicy(route);
                    return true;
                }
                else {
                    return false;
                }
            }
        }

        return false;
    }
}
