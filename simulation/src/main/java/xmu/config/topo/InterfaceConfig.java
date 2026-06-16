package xmu.config.topo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InterfaceConfig {
    public String segment;
    public String nextHop;
    public String deviceIp;
    public String interfaceName;
    public String peerIp;
    public String type;
}
