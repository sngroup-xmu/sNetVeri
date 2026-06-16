package xmu.config.topo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdgeConfig {
    public List<String> localIp;
    public List<String> remoteIp;

    public int localExportPolicyId;
    public int localImportPolicyId;

    public int remoteExportPolicyId;
    public int remoteImportPolicyId;

    public int remoteNodeId;
}
