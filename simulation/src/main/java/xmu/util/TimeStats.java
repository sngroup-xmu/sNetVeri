package xmu.util;

import java.util.concurrent.atomic.AtomicLong;

public final class TimeStats {
    private static final AtomicLong PRINT_TIME_NS = new AtomicLong(0);

    private TimeStats() {
    }

    public static void reset() {
        PRINT_TIME_NS.set(0);
    }

    public static void recordPrintTime(Runnable task) {
        long start = System.nanoTime();
        try {
            task.run();
        } finally {
            long end = System.nanoTime();
            PRINT_TIME_NS.addAndGet(end - start);
        }
    }

    public static double getPrintTimeMs() {
        return PRINT_TIME_NS.get() / 1_000_000.0;
    }

    public static double getSimulateTimeMs(long totalTimeNs) {
        long simulateTimeNs = totalTimeNs - PRINT_TIME_NS.get();
        return simulateTimeNs / 1_000_000.0;
    }

    public static void printReport(long totalTimeNs) {
        System.out.println("print time: " + getPrintTimeMs() + " ms");
        System.out.println("simulate time: " + getSimulateTimeMs(totalTimeNs) + " ms");
    }
}
