package xmu.config.topo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AggregateRouteConfig {
    public String segment;
    public int attributePolicyId;
    public  int suppressPolicyId;
    public String detailSuppress;
}
