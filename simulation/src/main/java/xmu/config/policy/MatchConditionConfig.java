package xmu.config.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchConditionConfig {

    public String type;        // ip_prefix_filter
    public String policy;      // 9.68.140.16/32
    public String matchMode;   // permit / deny
}