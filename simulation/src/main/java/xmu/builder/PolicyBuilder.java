package xmu.builder;


import xmu.config.policy.ApplyActionConfig;
import xmu.config.policy.MatchConditionConfig;
import xmu.config.policy.PolicyConfig;
import xmu.config.policy.PolicyRuleConfig;
import xmu.network.Context;
import xmu.network.others.AS;
import xmu.network.others.Subnet;
import xmu.network.policy.ApplyAction;
import xmu.network.policy.MatchCondition;
import xmu.network.policy.Policy;
import xmu.network.policy.PolicyRule;

import java.util.*;

public class PolicyBuilder {

    public static List<AS> buildAsPath(String asPathStr){
        List<AS> asPathList=new ArrayList<>();
        if (asPathStr == null || "null".equalsIgnoreCase(asPathStr.trim())) {
            asPathList= Collections.emptyList();
        } else {
            String[] parts = asPathStr.trim().split("\\s+");
            for (String part : parts) {
                if (!part.isEmpty()) {
                    asPathList.add(new AS(part));
                }
            }
        }
        return asPathList;
    }
    public static int buildAttribute(String attributeStr,boolean isLocalPref){
        if (attributeStr == null || "null".equalsIgnoreCase(attributeStr.trim())) {
            if(isLocalPref){
                return Context.LP_DEFAULT;
            }
            else {
                return 0;
            }
        }
        else {
            return Integer.parseInt(attributeStr);
        }
    }
    public static ApplyAction buildApplyAction(ApplyActionConfig applyActionConfig,Context cxt){
        byte asPathOp=Context.asOpToByte(applyActionConfig.asPathOperation);
        List<AS>asPathList=buildAsPath( applyActionConfig.asPath);

        //处理community
        String[] commParts=applyActionConfig.communityValue.trim().split("\\s+");
        int commOp=-1;
        Set<Integer> commParamSet=new HashSet<>();
        Set<Integer> communityValueSet=new HashSet<>();
        for (String commPart : commParts) {
            int id= cxt.registerCommunity(commPart);
            if (id>=Context.NULL_COMMUNITY_ID && id<=Context.DELETE_COMMUNITY_ID) {
                commOp=id;
            }
            else if(id>=Context.INTERNET_COMMUNITY_ID && id<=Context.NO_ADVERTISE_COMMUNITY_ID) {
                commParamSet.add(id);
            }
            else {
                communityValueSet.add(id);
                commOp=-1;
            }
        }

        ApplyAction apply=new ApplyAction(commOp,commParamSet,communityValueSet,asPathList,asPathOp,
                buildAttribute(applyActionConfig.med,false),
                buildAttribute(applyActionConfig.locPrf,true),
                buildAttribute(applyActionConfig.preferredValue,false));
        return apply;
    }

    public static MatchCondition buildMatchCondition(MatchConditionConfig mc,Context cxt){
        byte type=Context.filterToByte(mc.type);
        String policy=mc.policy;
        Subnet prefixFilter=null;
        byte greater=0;
        byte less=0;
        Set<Integer> communityValue=new HashSet<>();
        String asPathFilter = "";
        if (type==Context.IP_PREFIX_FILTER) {

            if(!policy.contains("*") && !policy.contains("~"))
            {
                prefixFilter=new Subnet(policy);
                greater=prefixFilter.getPrefixLength();
                less=prefixFilter.getPrefixLength();
            }
            else if(policy.contains("*")&& !policy.contains("~"))
            {
                String[] policyParts = policy.split("[*~]");
                prefixFilter=new Subnet(policyParts[0]);
                greater=Byte.parseByte(policyParts[1]);
                less=prefixFilter.getPrefixLength();
            }
            else if(policy.contains("~")&& !policy.contains("*")){
                String[] policyParts = policy.split("[*~]");
                prefixFilter=new Subnet(policyParts[0]);
                greater=prefixFilter.getPrefixLength();
                less=Byte.parseByte(policyParts[1]);
            }
            else {
                String[] policyParts = policy.split("[*~]");
                prefixFilter=new Subnet(policyParts[0]);
                greater=Byte.parseByte(policyParts[1]);
                less=Byte.parseByte(policyParts[2]);
            }
        }
        else if (type==Context.IP_COMMUNITY_FILTER) {
            String[] policyParts = policy.trim().split("\\s+");
            for(String p : policyParts){
                communityValue.add(cxt.registerCommunity(p));
            }
        }
        else if (type==Context.AS_PATH_FILTER) {
            asPathFilter=policy;
            // 如果规则以 `_` 开头或结尾，将其替换为 `^` 或 `$`
            if (asPathFilter.startsWith("_")) {
                asPathFilter = "[^\\s,{}()]?" + policy.substring(1);  // 将 `_` 替换为 `^`
            }
            if (asPathFilter.endsWith("_")) {
                asPathFilter = asPathFilter.substring(0, asPathFilter.length() - 1) + "[\\s,{}()$]?";  // 将 `_` 替换为 `$`
            }

            // 将 `_` 替换为匹配符号的正则表达式
            asPathFilter.replace("_", "[\\s,{}()]+");
        }
        boolean matchMode=true;
        if("deny".equalsIgnoreCase(mc.matchMode)){
            matchMode=false;
        }
        MatchCondition matchCondition= new MatchCondition(type,communityValue,asPathFilter,prefixFilter,greater,less,matchMode);
        return matchCondition;
    }
    public static Map<Byte,Set<MatchCondition>> buildMatchConditionMap(List<MatchConditionConfig> matchConditionConfigs, Context cxt){
        Map<Byte,Set<MatchCondition>> matchConditionMap=new HashMap<>();
        matchConditionConfigs.forEach(mc->{
            MatchCondition matchCondition=buildMatchCondition(mc,cxt);
            matchConditionMap.computeIfAbsent(matchCondition.getType(),k->new HashSet<>()).add(matchCondition);
        });
        return matchConditionMap;
    }

    public static PolicyRule buildPolicyRule(PolicyRuleConfig config, Context cxt){
        int node=Integer.parseInt(config.node);
        boolean rule=true;
        if ("deny".equalsIgnoreCase(config.rule)){
            rule=false;
        }
        Map<Byte,Set<MatchCondition>> matchConditionMap=buildMatchConditionMap(config.matchConditions,cxt);
        ApplyAction applyAction=buildApplyAction(config.applyAction,cxt);
        PolicyRule policyRule=new PolicyRule(node,rule,matchConditionMap,applyAction);
        return policyRule;
    }

    public static Policy buildPolicy(PolicyConfig config, Context cxt){
        int id=config.id;
        List<PolicyRule> policyRules=new ArrayList<>();
        config.policy.forEach(p->{
            PolicyRule policyRule=buildPolicyRule(p,cxt);
            policyRules.add(policyRule);
        });
        return new Policy(id,policyRules);
    }
    public static void build(List<PolicyConfig> policyConfigs,Context cxt){
        policyConfigs.forEach(p->{
            Policy policy=buildPolicy(p,cxt);
            cxt.registerPolicy(policy);
        });
    }
}
