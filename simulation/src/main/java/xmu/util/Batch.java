package xmu.util;

import xmu.network.others.Subnet;

import java.util.*;
import java.util.stream.Collectors;

public class Batch {
    public static List<Set<Subnet>> sharding(int batchSize, List<Subnet> subnetList){

        if (batchSize <= 0 || subnetList == null || subnetList.isEmpty()) {
            throw new IllegalArgumentException("batchSize <= 0 or subnetList == null");
        }
        List<Set<Subnet>> result = new ArrayList<>();
        int size = subnetList.size();
        List<Subnet> sortedSubnet=subnetList.stream().sorted().collect(Collectors.toList());
        for (int i = 0; i < size; i += batchSize) {
            Set<Subnet> batch = new LinkedHashSet<>();

            // 当前 batch 的结束位置
            int end = Math.min(i + batchSize, size);

            for (int j = i; j < end; j++) {
                batch.add(sortedSubnet.get(j));
            }
            result.add(batch);
        }

        return result;
    }
}
