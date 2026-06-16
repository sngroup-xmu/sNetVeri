package xmu.network;



import xmu.network.others.Subnet;
import xmu.network.others.AS;
import xmu.network.others.IP;
import xmu.network.policy.Policy;
import xmu.network.topo.Node;
import xmu.network.topo.PhysicalNode;

import java.util.*;

public class Context {

    // ===== NULL =====
    public static final int NULL = 0;
    // ===== INTERFACE =====
    public static final int NULL0_INTERFACE_ID = 0;

    // ===== COMMUNITY =====
    public static final int NULL_COMMUNITY_ID = 0;
    public static final int NONE_COMMUNITY_ID = 1;
    public static final int ADDITIVE_COMMUNITY_ID = 2;
    public static final int DELETE_COMMUNITY_ID = 3;
    public static final int INTERNET_COMMUNITY_ID = 4;
    public static final int NO_EXPORT_COMMUNITY_ID = 5;
    public static final int NO_EXPORT_CONFED_COMMUNITY_ID = 6;
    public static final int NO_ADVERTISE_COMMUNITY_ID = 7;
    // ===== AS OPERATION=====
    public static final byte AS_ADDITIVE=1;
    public static final byte AS_OVERWRITE=2;
    public static final byte AS_NONE=3;
    public static final byte AS_DELETE=4;

    // =====MATCH CONDITION FILTER TYPE=====
    public static final byte IP_PREFIX_FILTER=1;
    public static final byte IP_COMMUNITY_FILTER=2;
    public static final byte AS_PATH_FILTER=3;

    // ===== NODE ROLE =====
    public static final byte TOR=1;
    public static final byte AGG=2;
    public static final byte CORE=3;
    public static final byte ROUTE=4;

    //===== PROTOCOL =====
    public static final int PRO_STATIC=0;
    public static final int PRO_DIRECT=1;
    public static final int PRO_BGP=3;
    public static final int PRO_EBGP=4;
    public static final int PRO_IBGP=5;
    // BGP
    //===== FLAGS =====
    public  static final byte FLAGS_R=0; //中继
    public  static final byte FLAGS_D=1;    //直连
    public  static final byte FLAGS_T=2; //tovpn
    public  static final byte FLAGS_B=3; //黑洞

    //===== origin =====
    public static final byte ORI_IGP=2; //network
    public static final byte ORI_EGP=1; //EGP
    public static final byte ORI_INCOM=0; //import
    //===== method =====
    public static final byte MET_AGG=5;
    public static final byte MET_AGGAUTO=4;
    public static final byte MET_NET=3;
    public static final byte MET_IMP=2;
    public static final byte MET_PEER=1;
    //===== statesCode =====
    public static final byte CODE_BEST=3;
    public static final byte CODE_VALID=2;
    public static final byte CODE_SUPPRESSED=1;

    //===== LocalPre =====
    public static final int LP_DEFAULT=100;

    //===== Preference =====
    public static final int PRE_DIRECT_DEFAULT=0;
    public static final int PRE_STATIC_DEFAULT=60;
    //====== COST ======
    public static final int COST_DEFAULT=0;
    //全局节点和策略表
    private Map<Integer, Node> nodeMap=new HashMap<>();
    private Map<Integer, Policy> policyMap=new HashMap<>();
    private Map<IP, Node> routerIdNodeMap=new HashMap<>();
    //
    private Map<Subnet,Set<Integer>> AllBgpPrefix = new HashMap<>();
    private Map<Subnet,Set<Integer>> AllAggPrefix = new HashMap<>();


    //接口，社区号，as号
    private  Map<Integer,String> interfaceNameMap=new HashMap<>();
    private  Map<String,Integer> interfaceToIdMap=new HashMap<>();
    private  Map<Integer,String> communityMap=new HashMap<>();
    private  Map<String, Integer> communityToIdMap = new HashMap<>();
    private  Map<IP, AS> ipToAsMap=new HashMap<>(); //真实-routerid
    private  Map<IP,IP> deviceIptoRouterIdMap=new HashMap<>();
    private  Map<IP,IP> routerIdToDeviceIpMap=new HashMap<>();
    public Context() {
        registerInterfaceName("NULL0");
        registerCommunity("null");
        registerCommunity("none");
        registerCommunity("additive");
        registerCommunity("delete");
        registerCommunity("internet");
        registerCommunity("no-export");
        registerCommunity("no-export-confed");
        registerCommunity("no-advertise");
    }
    // ===== ID 管理 =====
    private int interfaceIdCounter = 0;

    public int nextInterfaceId() {
        return interfaceIdCounter++;
    }

    private int communityIdCounter = 0;

    public int nextCommunityId() {
        return communityIdCounter++;
    }

    // ===== 注册 =====
    public void registerNode(Node node) {
        nodeMap.put(node.getNodeId(), node);
    }
    public void registerRouterIdNode( Node node) {
        for (PhysicalNode physicalNode : node.getPhysicalNodeMap().values()) {
            routerIdNodeMap.put(physicalNode.getRouterId(), node);
        }

    }
    public void registerPolicy(Policy policy) {
        policyMap.put(policy.getId(),policy);
    }

    public int registerInterfaceName(String interfaceName) {
        if (!interfaceToIdMap.containsKey(interfaceName)) {
            interfaceNameMap.put(interfaceIdCounter, interfaceName);
            interfaceToIdMap.put(interfaceName,interfaceIdCounter);
            nextInterfaceId();
        }
        return interfaceToIdMap.get(interfaceName);
    }

    public int registerCommunity(String community) {
        if (!communityToIdMap.containsKey(community)) {

            communityMap.put(communityIdCounter, community);
            communityToIdMap.put(community,communityIdCounter);
            nextCommunityId();
        }
        return communityToIdMap.get(community);
    }


    public void registerAsIP(IP ip,AS as) {
        ipToAsMap.put(ip,as);
    }
    public void registerRouterId(IP routerId,IP ip) {
        deviceIptoRouterIdMap.put(ip,routerId);
        routerIdToDeviceIpMap.put(routerId,ip);
    }
    public void registerBgpPrefix(Subnet subnet,int nodeId) {
        AllBgpPrefix.computeIfAbsent(subnet,k->new HashSet<>()).add(nodeId);
    }

    public void registerAggPrefix(Subnet subnet,int nodeId) {
        AllAggPrefix.computeIfAbsent(subnet,k->new HashSet<>()).add(nodeId);
    }

    // ===== 查询方法 =====
    public Node getNode(int nodeId) {
        return nodeMap.get(nodeId);
    }
    public Node getNode(IP routerId) {
        return routerIdNodeMap.get(routerId);
    }
    public Policy getPolicy(int policyId) {
        return policyMap.get(policyId);
    }

    public String getInterfaceName(int interfaceId) {
        return interfaceNameMap.get(interfaceId);
    }
    public String getCommunity(int communityId) {
        return communityMap.get(communityId);
    }

    public AS getAS(IP ip) {
        return ipToAsMap.get(ip);
    }


    public IP getDeviceIP(IP routerId) {
        return routerIdToDeviceIpMap.get(routerId);
    }
    public IP getRouterId(IP deviceIP) {
        return deviceIptoRouterIdMap.get(deviceIP);
    }
    public Map<Integer, Node> getNodeMap() {
        return nodeMap;
    }

    public Map<Subnet, Set<Integer>> getAllAggPrefix() {
        return AllAggPrefix;
    }
    public Map<Subnet, Set<Integer>> getAllBgpPrefix() {
        return AllBgpPrefix;
    }


    public static byte filterToByte(String filter) {
        switch (filter) {
            case "ip_prefix_filter": return IP_PREFIX_FILTER;
            case "ip_community_filter": return IP_COMMUNITY_FILTER;
            case "as_path_filter": return AS_PATH_FILTER;
            default: return NULL;
        }
    }

    public static byte asOpToByte(String op) {
        switch (op) {
            case "additive": return AS_ADDITIVE;
            case "delete": return AS_DELETE;
            case "overwrite": return AS_OVERWRITE;
            case "none": return AS_NONE;
            default: return NULL;
        }
    }
    public static byte protocolToByte(String protocol) {
        if (protocol.equals("STATIC")) {
            return PRO_STATIC;
        }
        else
        {
            return PRO_DIRECT;
        }
    }
    public static Byte roleToByte(String role) {
        switch (role) {
            case "TOR": return TOR;
            case "AGGREGATION": return AGG;
            case "CORE": return CORE;
            default: return ROUTE;
        }
    }
    public static String protocolToString(int proto) {
        switch (proto) {
            case PRO_STATIC: return "Static";
            case PRO_DIRECT: return "Direct";
            case PRO_BGP: return "BGP";
            case PRO_EBGP: return "EBGP";
            case PRO_IBGP: return "IBGP";
            default: return "UNKNOWN";
        }
    }
    public static String originToString(byte origin) {
        switch (origin) {
            case ORI_INCOM: return "?";
            case ORI_EGP: return "e";
            case ORI_IGP: return "i";
            default: return "UNKNOWN";
        }
    }
    public static String codeToString(Set<Byte> codes) {
        List<Byte> codeList = new ArrayList<>(codes);
        Collections.sort(codeList);
        if (codeList.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Byte code : codeList) {
            if(code == CODE_SUPPRESSED){
                sb.append("suppressed");
            }
            else if(code == CODE_VALID){
                sb.append("valid");
            }
            else if(code == CODE_BEST){
                sb.append("best");
            }
            sb.append("-");
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    public static String flagToString(Set<Byte> flags) {
        List<Byte> flagList = new ArrayList<>(flags);
        Collections.sort(flagList);
        StringBuilder sb = new StringBuilder();
        for (Byte code : flagList) {
            switch (code) {
                case FLAGS_R : sb.append("R");break;
                case FLAGS_D : sb.append("D");break;
                case FLAGS_T : sb.append("T");break;
                case FLAGS_B : sb.append("B");break;
                default: sb.append("UNKNOWN");break;
            }
        }
        return sb.toString();
    }
}
