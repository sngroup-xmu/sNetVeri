package xmu.config.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyRuleConfig {

    public String node;   // 注意是 String（JSON 是字符串）
    public String rule;   // permit / deny

    public List<MatchConditionConfig> matchConditions;

    @JsonProperty("ApplyActionDto")
    public ApplyActionConfig applyAction;
}