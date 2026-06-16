package xmu.input;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import xmu.config.policy.PolicyConfig;
import xmu.config.topo.NodeConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class FileParser {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static List<NodeConfig> parseTopology(String filePath) {
        try {
            Path path = Paths.get(filePath, "topo.json");
            return mapper.readValue(
                    path.toFile(),
                    new TypeReference<List<NodeConfig>>() {}
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse topology file", e);
        }
    }

    public static List<PolicyConfig> parsePolicy(String filePath) {
        try {
            Path path = Paths.get(filePath, "policy.json");
            return mapper.readValue(
                    path.toFile(),
                    new TypeReference<List<PolicyConfig>>() {}
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse policy file", e);
        }
    }
}
