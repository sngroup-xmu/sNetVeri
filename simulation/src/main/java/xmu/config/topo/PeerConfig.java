package xmu.config.topo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PeerConfig {

    public String localIp;
    public String remoteIp;

    public String remoteAsNumber;
    public String localAsNumber;

    public String localPeerIp;
    public String remotePeerIp;

    public String localInterfaceName;
    public String remoteInterfaceName;
}
