package xmu.config.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyConfig {

    public String name;
    public int id;

    public List<PolicyRuleConfig> policy;

}

