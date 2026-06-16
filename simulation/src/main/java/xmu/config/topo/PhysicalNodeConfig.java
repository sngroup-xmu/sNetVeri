package xmu.config.topo;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
@JsonIgnoreProperties(ignoreUnknown = true)
public class PhysicalNodeConfig {

    public String deviceIp;
    public String asNum;
    public String routerId;

    public List<InterfaceConfig> interfaceList;
    public List<PeerConfig> peerList;

    public String vrfName;

    public String maxLbNum;
    public String lbAsPathRelax;

    public String preferenceExternal;
    public String preferenceInternal;
    public String preferenceLocal;

    @JsonProperty("NetworkRouteDto")
    public List<NetworkRouteConfig> networkRouteList;

    @JsonProperty("ImportRouteDto")
    public List<ImportRouteConfig> importRouteList;

    @JsonProperty("AggregateRouteDto")
    public List<AggregateRouteConfig> aggregateRouteList;
}
