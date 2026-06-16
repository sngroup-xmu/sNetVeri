package xmu.config.topo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeConfig {

    @JsonProperty("NodeId")
    public int nodeId;

    public String deviceName;
    public List<String> deviceIp;
    public String deviceRole;
    public String region;
    public String logiArea;
    public String deviceGroup;
    public String asNum;
    public List<PhysicalNodeConfig> physicalNodeList;
    public List<EdgeConfig> edgeList;
}