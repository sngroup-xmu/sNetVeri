package xmu.input;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import xmu.network.others.IP;

import java.util.ArrayList;
import java.util.List;

public class CLIParser {

//    @Option(names = {"-m", "--mode"}, required = true)
//    private String mode; //用于指定是初始还是增量
//
//    @Option(names = {"-t", "--type"}, required = true)
//    private String type; //用于指定仿真器的类型

    @Option(names = {"-c", "--configPath"}, required = true)
    private String configPath; //用于指定拓扑的位置

    @Option(names = {"-b", "--batchSize"}, required = true)
    private int  batchSize; //用于指定batch的大小

    @Option(names = {"-p", "--print"})
    private int  print; //用于指定print的种类

    @Option(names ={ "-pd","--printDevice"}, split = ",")
    List<String> device;
//    @Option(names = {"--time-limit"})
//    private double timeLimit = 1000;
//
//    @Option(names = {"-o", "--output"})
//    private String outputPath = "output.log";

    public static final int WO_PRINT=0;
    public static final int COM_PRINT=1;
    public static CLIParser parse(String[] args) {
        CLIParser cli = new CLIParser();
        CommandLine cmd = new CommandLine(cli);
        cmd.parseArgs(args);
        return cli;
    }

    public int getBatchSize() {
        return batchSize;
    }
    public int getPrint() {
        return print;
    }
    public String getConfigPath() {
        return configPath;
    }
    public List<IP> getDevice() {
        List<IP> deviceIpList = new ArrayList<IP>();
        for (String s : device) {
            IP ip = new IP(s);
            deviceIpList.add(ip);
        }
        return deviceIpList;
    }
}