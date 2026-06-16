package xmu.config.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApplyActionConfig {

    public String communityValue;

    public String asPath;
    public String asPathOperation;

    public String med;
    public String locPrf;

    public String preferredValue;
}
