package xmu.network.policy;


import xmu.network.Context;
import xmu.network.others.AS;
import xmu.network.routes.BGPRoute;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ApplyAction {
    private int commOp;
    private Set<Integer> commParam=new HashSet<>();
    private Set<Integer> communityValue=new HashSet<>();

    private List<AS> asPath=new ArrayList<>(); //存在两种形式 x.y 或 x

    private byte  asPathOp;
    private int   med; //1～1000，缺省值为1
    private int   locPrf; //0～4294967295 缺省情况下，BGP本地优先级的值为100
    private int   preferredValue; //0～65535 缺省情况下，从对等体学来的路由的初始首选值为0

    public ApplyAction(int commOp, Set<Integer> commParam, Set<Integer> communityValue, List<AS> asPath,byte asPathOp,int med, int locPrf, int preferredValue) {
        this.commOp = commOp;
        this.commParam = commParam;
        this.communityValue = communityValue;
        this.asPath = asPath;
        this.asPathOp = asPathOp;
        this.med = med;
        this.locPrf = locPrf;
        this.preferredValue = preferredValue;
    }

    public void applycommunity (BGPRoute bgpRoute){

        if(commOp== Context.NONE_COMMUNITY_ID){
            bgpRoute.getCommunityValue().clear();
        }
        else if(commOp==Context.ADDITIVE_COMMUNITY_ID){
            bgpRoute.getCommunityValue().addAll(communityValue);
            bgpRoute.getCommunityValue().addAll(commParam);
        }
        else if(commOp==Context.DELETE_COMMUNITY_ID){
            bgpRoute.getCommunityValue().removeAll(communityValue);
            bgpRoute.getCommunityValue().removeAll(commParam);
        }
        else if(commOp==Context.NULL_COMMUNITY_ID) {
            return;
        }
        else {
            // 中文注释：overwrite 模式需要先清空，再整体写入目标 community 集合。
            bgpRoute.getCommunityValue().clear();
            bgpRoute.getCommunityValue().addAll(communityValue);
            bgpRoute.getCommunityValue().addAll(commParam);
        }
    }
    public void applyAsPath(BGPRoute bgpRoute){
        if(asPathOp==Context.AS_NONE){
            bgpRoute.getAsPath().clear();
        }
        else if(asPathOp==Context.AS_ADDITIVE){
            bgpRoute.getAsPath().addAll(asPath);
        }
        else if(asPathOp==Context.AS_OVERWRITE){
            bgpRoute.getAsPath().clear();
            bgpRoute.getAsPath().addAll(asPath);
        }
        else if(asPathOp==Context.AS_DELETE){
            bgpRoute.getAsPath().removeAll(asPath);
        }

    }
    public void applyLocPrf( BGPRoute bgpRoute){
        bgpRoute.setLocPrf(locPrf);
    }
    public void applyPreferredValue( BGPRoute bgpRoute){
        bgpRoute.setPreferredValue(preferredValue);
    }
    public void applyMed(BGPRoute bgpRoute){
        bgpRoute.setMed(med);
    }
    public void applyPolicy(BGPRoute bgpRoute){
        applyPreferredValue(bgpRoute);
        applyLocPrf(bgpRoute);
        applyMed(bgpRoute);
        applyAsPath(bgpRoute);
        applycommunity(bgpRoute);
    }
}
