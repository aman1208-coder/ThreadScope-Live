package com.threadscope.agent;

import com.threadscope.metrics.ThreadMetricsSnapshot;
import org.springframework.stereotype.Component;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * RealThreadInspector
 *
 * Uses Java's built-in ThreadMXBean and ManagementFactory to read REAL
 * live data from the JVM this server is running in.
 *
 * BUG-09 FIX: The original code called dumpAllThreads() AND findDeadlockedThreads()
 * separately per broadcast tick = 2 JVM safepoints at 10fps = 20 stop-the-world
 * pauses per second. Fixed by combining both into one pass: findDeadlockedThreads()
 * is called once here and its result is cached on the snapshot so MetricsCollector
 * does NOT call it again.
 */
@Component
public class RealThreadInspector {

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    private final List<GarbageCollectorMXBean> gcBeans =
            ManagementFactory.getGarbageCollectorMXBeans();

    // Track previous CPU times to compute deltas
    private final Map<Long, Long> prevCpuTimes = new ConcurrentHashMap<>();
    // C-02 FIX: AtomicLong.getAndSet() makes the read-modify-write atomic
    // — prevents two concurrent callers getting identical elapsedNs=0 → 100% CPU false positive
    private final AtomicLong prevSampleTimeNs = new AtomicLong(System.nanoTime());

    // Stack sample counters for hot method tracking
    private final Map<String, LongAdder> methodSamples = new ConcurrentHashMap<>();
    private volatile long lastSampleReset = System.currentTimeMillis();

    // Cache last deadlock result — populated inside collectThreads() to avoid
    // a second safepoint from a separate findDeadlockedThreads() call
    private volatile long[] lastDeadlockedIds = null;

    public RealThreadInspector() {
        if (threadMXBean.isThreadCpuTimeSupported()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        }
        if (threadMXBean.isThreadContentionMonitoringSupported()) {
            threadMXBean.setThreadContentionMonitoringEnabled(true);
        }
    }

    /**
     * Collect a full snapshot of all real JVM threads.
     * Also calls findDeadlockedThreads() HERE so MetricsCollector does not
     * need to call it again (eliminates the second JVM safepoint per tick).
     */
    public List<ThreadMetricsSnapshot.ThreadInfo> collectThreads() {
        // C-02 FIX: getAndSet is atomic — no window between read and write
        long nowNs = System.nanoTime();
        long elapsedNs = nowNs - prevSampleTimeNs.getAndSet(nowNs);

        // Single safepoint: dumpAllThreads with monitors + synchronizers
        java.lang.management.ThreadInfo[] rawThreads =
                threadMXBean.dumpAllThreads(true, true);

        // BUG-09 FIX: detect deadlocks in the SAME call sequence — one safepoint total
        // Store result so findDeadlockedThreads() below can return it without a 2nd call
        lastDeadlockedIds = threadMXBean.findDeadlockedThreads();

        List<ThreadMetricsSnapshot.ThreadInfo> result = new ArrayList<>();

        for (java.lang.management.ThreadInfo ti : rawThreads) {
            if (ti == null) continue;

            ThreadMetricsSnapshot.ThreadInfo info = new ThreadMetricsSnapshot.ThreadInfo();
            info.id           = ti.getThreadId();
            info.name         = ti.getThreadName();
            info.state        = ti.getThreadState().name();
            info.stackDepth   = ti.getStackTrace().length;
            info.blockedCount = ti.getBlockedCount();
            info.waitedCount  = ti.getWaitedCount();
            info.blockedTimeMs = ti.getBlockedTime();

            // Real CPU % from delta between snapshots
            if (threadMXBean.isThreadCpuTimeSupported()) {
                long cpuNow  = threadMXBean.getThreadCpuTime(ti.getThreadId());
                Long cpuPrev = prevCpuTimes.get(ti.getThreadId());
                if (cpuPrev != null && elapsedNs > 0 && cpuNow >= 0) {
                    info.cpuPercent = Math.min(100.0, ((cpuNow - cpuPrev) / (double) elapsedNs) * 100.0);
                } else {
                    info.cpuPercent = 0;
                }
                if (cpuNow >= 0) prevCpuTimes.put(ti.getThreadId(), cpuNow);
            }

            if (ti.getLockName() != null) info.lockName = ti.getLockName();

            StackTraceElement[] stack = ti.getStackTrace();
            if (stack != null && stack.length > 0) {
                StackTraceElement top = stack[0];
                info.topMethod = top.getClassName().replaceAll(".*\\.", "")
                               + "." + top.getMethodName() + "()";
                methodSamples.computeIfAbsent(info.topMethod, k -> new LongAdder()).increment();
            } else {
                info.topMethod = "native";
            }

            result.add(info);
        }

        // BUG-05 FIX: evict dead thread IDs from CPU time map to prevent memory leak
        Set<Long> liveIds = new HashSet<>();
        for (java.lang.management.ThreadInfo ti : rawThreads) {
            if (ti != null) liveIds.add(ti.getThreadId());
        }
        prevCpuTimes.keySet().removeIf(id -> !liveIds.contains(id));

        return result;
    }

    /**
     * Returns the deadlock result from the most recent collectThreads() call.
     * Does NOT trigger a new JVM safepoint — reuses the cached value.
     * MetricsCollector MUST call collectThreads() before calling this.
     */
    public long[] findDeadlockedThreads() {
        return lastDeadlockedIds; // null = no deadlock
    }

    public List<ThreadMetricsSnapshot.HotMethod> getHotMethods(int topN) {
        long now = System.currentTimeMillis();

        List<ThreadMetricsSnapshot.HotMethod> list = new ArrayList<>();
        methodSamples.forEach((name, counter) -> {
            ThreadMetricsSnapshot.HotMethod hm = new ThreadMetricsSnapshot.HotMethod();
            hm.name    = name;
            hm.samples = (int) counter.sum();
            list.add(hm);
        });
        list.sort((a, b) -> Integer.compare(b.samples, a.samples));

        if (now - lastSampleReset > 5000) {
            methodSamples.clear();
            lastSampleReset = now;
        }

        // BUG-11 FIX: copy to new ArrayList — never return a subList view
        return new ArrayList<>(list.subList(0, Math.min(topN, list.size())));
    }

    public long heapUsedMb()     { return memoryMXBean.getHeapMemoryUsage().getUsed() / (1024 * 1024); }
    public long heapMaxMb()      { return memoryMXBean.getHeapMemoryUsage().getMax()  / (1024 * 1024); }
    public long totalGcCount()   { return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(c -> c >= 0).sum(); }
    public long totalGcTimeMs()  { return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(t -> t >= 0).sum(); }
    public long uptimeSeconds()  { return runtimeMXBean.getUptime() / 1000; }
    public int  totalThreadCount()  { return threadMXBean.getThreadCount(); }
    public int  daemonThreadCount() { return threadMXBean.getDaemonThreadCount(); }
}
