package xmu;

import xmu.builder.NodeBuilder;
import xmu.builder.PolicyBuilder;
import xmu.config.policy.PolicyConfig;
import xmu.config.topo.NodeConfig;
import xmu.input.CLIParser;
import xmu.input.FileParser;
import xmu.network.Context;
import xmu.network.routes.RouteMap;
import xmu.simulator.Dijkstra.Dijkstra;
import xmu.util.TimeStats;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        TimeStats.reset();
        long startTime = System.nanoTime();
        CLIParser cli= CLIParser.parse(args);
        List<NodeConfig> nodes= FileParser.parseTopology(cli.getConfigPath());
        List<PolicyConfig> policies= FileParser.parsePolicy(cli.getConfigPath());
        int printtype=cli.getPrint();
        int batchSize=cli.getBatchSize();
        Context context=new Context();
        NodeBuilder.build(nodes,context);
        PolicyBuilder.build(policies,context);
        RouteMap.routeMap(context);

        Dijkstra.run(batchSize,cli.getConfigPath(),printtype,context);
        long endTime = System.nanoTime();    //  结束

        long durationNs = endTime - startTime;

        TimeStats.printReport(durationNs);
        System.out.println("total time: " + durationNs / 1_000_000.0 + " ms");;
    }
}