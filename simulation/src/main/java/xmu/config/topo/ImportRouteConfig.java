package xmu.config.topo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportRouteConfig {
    public   String protocol ; //1 direct 0 static
    public   int id;
}
