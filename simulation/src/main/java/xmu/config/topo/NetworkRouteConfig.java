package xmu.config.topo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkRouteConfig {
    public String segment;
    public int id;
}
