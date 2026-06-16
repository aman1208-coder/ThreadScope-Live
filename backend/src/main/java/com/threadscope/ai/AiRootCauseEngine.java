package com.threadscope.ai;

import com.threadscope.metrics.ThreadMetricsSnapshot;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * AiRootCauseEngine  — thread-safe rule-based diagnosis engine.
 *
 * BUG-08 FIX: prevSpinCount now AtomicLong; lastIssueName/lastIssueTs
 * are volatile; the public analyse() method is synchronized so the
 * @Scheduled broadcast and REST /api/ai threads can never interleave.
 */
@Component
public class AiRootCauseEngine {

    private static final int WINDOW = 30;

    private final MetricWindow wLatency      = new MetricWindow(WINDOW);
    private final MetricWindow wQueueDepth   = new MetricWindow(WINDOW);
    private final MetricWindow wThroughput   = new MetricWindow(WINDOW);
    private final MetricWindow wBlockedPct   = new MetricWindow(WINDOW);
    private final MetricWindow wCpuAvg       = new MetricWindow(WINDOW);
    private final MetricWindow wHeapPct      = new MetricWindow(WINDOW);
    private final MetricWindow wSpinDelta    = new MetricWindow(WINDOW);
    private final MetricWindow wGcTimeMs     = new MetricWindow(WINDOW);

    // BUG-08 FIX: use AtomicLong / volatile for shared mutable state
    private final AtomicLong prevSpinCount = new AtomicLong(0);
    private volatile String  lastIssueName = "";
    private volatile long    lastIssueTs   = 0;

    private final ArrayBlockingQueue<AiDiagnosis.HistoryEntry> history =
            new ArrayBlockingQueue<>(10);

    // ── Public API — synchronized to guard MetricWindows and history ─

    // FIX: track tick count to suppress diagnoses during cold-start
    // (first 6 ticks = 600ms — MetricWindow needs ≥6 points for trend detection)
    private int warmupTicks = 0;
    private static final int WARMUP_REQUIRED = 6;

    public synchronized AiDiagnosis analyse(ThreadMetricsSnapshot snap) {
        feedWindows(snap);
        warmupTicks++;

        // During warm-up, only return Healthy — windows have insufficient data for rules
        if (warmupTicks < WARMUP_REQUIRED) return buildWarmupDiagnosis();

        AiDiagnosis diagnosis = evaluateDeadlock(snap);
        if (diagnosis == null) diagnosis = evaluateLockContention(snap);
        if (diagnosis == null) diagnosis = evaluateConsumerBackpressure(snap);
        if (diagnosis == null) diagnosis = evaluateCpuSaturation(snap);
        if (diagnosis == null) diagnosis = evaluateMemoryPressure(snap);
        if (diagnosis == null) diagnosis = evaluateHealthy();

        updateHistory(diagnosis);
        // FIX: toArray() on ABQ is thread-safe; new ArrayList(queue) iterates the queue
        // which is safe per ABQ contract, but toArray snapshot is more explicit
        diagnosis.history = new ArrayList<>(Arrays.asList(history.toArray(new AiDiagnosis.HistoryEntry[0])));

        // ── Attach health score (0–100) to every diagnosis ──────────
        diagnosis.healthScore = computeHealthScore(snap, diagnosis.severity);

        return diagnosis;
    }

    private AiDiagnosis buildWarmupDiagnosis() {
        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "Warming Up";
        d.severity       = AiDiagnosis.SEV_HEALTHY;
        d.confidence     = 70;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = List.of("warmupTicks=" + warmupTicks + "/" + WARMUP_REQUIRED);
        d.rootCause      = "Collecting baseline metrics (" + warmupTicks + "/" + WARMUP_REQUIRED + " ticks). Diagnosis available shortly.";
        d.recommendation = "Wait for data collection to complete.";
        d.history        = new ArrayList<>(history);
        d.healthScore    = 100;
        return d;
    }

    // ── Window feeding ────────────────────────────────────────────────

    private void feedWindows(ThreadMetricsSnapshot snap) {
        if (snap.disruptor != null) {
            wLatency.add(snap.disruptor.latencyMs);
            wQueueDepth.add(snap.disruptor.queueDepth);
            wThroughput.add(snap.disruptor.eventsPerSec);
            long spinNow   = snap.disruptor.spinCount;
            long spinPrev  = prevSpinCount.getAndSet(spinNow);
            wSpinDelta.add(Math.max(0, spinNow - spinPrev));
        }
        if (snap.threads != null && !snap.threads.isEmpty()) {
            long blocked = snap.threads.stream()
                    .filter(t -> "BLOCKED".equals(t.state)).count();
            wBlockedPct.add(100.0 * blocked / snap.threads.size());
            double avgCpu = snap.threads.stream()
                    .mapToDouble(t -> t.cpuPercent).average().orElse(0.0);
            wCpuAvg.add(avgCpu);
        }
        if (snap.jvm != null && snap.jvm.heapMaxMb > 0) {
            double heapPct = 100.0 * snap.jvm.heapUsedMb / snap.jvm.heapMaxMb;
            wHeapPct.add(heapPct);
            wGcTimeMs.add(snap.jvm.gcTimeMs);
        }
    }

    // ── Health Score (0–100) ─────────────────────────────────────────

    private int computeHealthScore(ThreadMetricsSnapshot snap, String severity) {
        // Start at 100, deduct based on severity + metric signals
        double score = 100.0;
        switch (severity) {
            case AiDiagnosis.SEV_CRITICAL -> score -= 55;
            case AiDiagnosis.SEV_HIGH     -> score -= 35;
            case AiDiagnosis.SEV_MEDIUM   -> score -= 18;
            case AiDiagnosis.SEV_LOW      -> score -= 8;
        }
        // Additional deductions from raw metrics
        double blocked = wBlockedPct.latest();
        double latency = wLatency.latest();
        double heap    = wHeapPct.latest();
        double queue   = wQueueDepth.latest();

        score -= MetricWindow.clamp01(blocked / 60.0) * 15;
        score -= MetricWindow.clamp01(latency  / 10.0) * 12;
        score -= MetricWindow.clamp01((heap - 60) / 35.0) * 8;
        score -= MetricWindow.clamp01(queue / 800.0) * 10;

        return (int) Math.max(0, Math.min(100, Math.round(score)));
    }

    // ── Rule 1: Deadlock ─────────────────────────────────────────────

    private AiDiagnosis evaluateDeadlock(ThreadMetricsSnapshot snap) {
        if (snap.jvm == null) return null;
        long[] deadlocked = snap.jvm.deadlockedThreadIds;
        if (deadlocked == null || deadlocked.length == 0) return null;

        int count      = deadlocked.length;
        int confidence = Math.min(99, 85 + Math.min(count * 2, 14));

        List<String> signals = new ArrayList<>();
        signals.add("deadlockedThreads=" + count);
        signals.add("threadIds=" + Arrays.toString(deadlocked));
        if (snap.disruptor != null)
            signals.add("latencyMs=" + String.format("%.2f", snap.disruptor.latencyMs));

        String lockDetail = "";
        if (snap.threads != null) {
            String locks = snap.threads.stream()
                    .filter(t -> "BLOCKED".equals(t.state) && t.lockName != null)
                    .map(t -> t.lockName).distinct().limit(3)
                    .collect(Collectors.joining(", "));
            if (!locks.isEmpty()) {
                lockDetail = " Circular wait on: " + locks + ".";
                signals.add("locksInvolved=" + locks);
            }
        }
        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "Deadlock";
        d.severity       = AiDiagnosis.SEV_CRITICAL;
        d.confidence     = confidence;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = signals;
        d.rootCause      = count + " threads in a deadlock cycle — each holds a lock the other needs." + lockDetail +
                           " ThreadMXBean.findDeadlockedThreads() returned " + count + " thread IDs.";
        d.recommendation = "Enforce global lock-ordering. Use ReentrantLock.tryLock(timeout). " +
                           "Refactor to LMAX Disruptor pattern to eliminate shared-lock deadlocks.";
        return d;
    }

    // ── Rule 2: Lock Contention ──────────────────────────────────────

    private AiDiagnosis evaluateLockContention(ThreadMetricsSnapshot snap) {
        double blockedPct = wBlockedPct.latest();
        double latency    = wLatency.latest();
        double spinDelta  = wSpinDelta.latest();

        boolean highBlocked = blockedPct >= 25.0;
        boolean risingLat   = wLatency.isTrendingUp(0.15) || latency > 1.5;
        boolean spinHot     = spinDelta > 5_000 || wSpinDelta.isTrendingUp(0.25);
        boolean sustained   = wBlockedPct.sustainedFraction(20.0) >= 0.4;

        // A-01 FIX: highBlocked is now MANDATORY — spin-wait alone (no blocked threads)
        // must NOT trigger Lock Contention. PhasedBackoffWaitStrategy generates thousands
        // of spins per tick during normal operation; requiring blocked threads prevents
        // false positives during healthy Disruptor operation.
        if (!highBlocked) return null;
        int sig = 2 + (risingLat ? 2 : 0) + (spinHot ? 1 : 0) + (sustained ? 1 : 0);
        if (sig < 3) return null;

        double cBase = 0;
        cBase += MetricWindow.clamp01(blockedPct / 60.0) * 35;
        cBase += MetricWindow.clamp01(latency / 8.0) * 25;
        cBase += MetricWindow.clamp01(spinDelta / 50_000.0) * 20;
        cBase += wBlockedPct.sustainedFraction(20.0) * 15;
        int confidence = (int) Math.round(clampConf(cBase));

        int blockedCount = snap.threads == null ? 0 : (int) snap.threads.stream()
                .filter(t -> "BLOCKED".equals(t.state)).count();
        String topLock = snap.threads == null ? "unknown" : snap.threads.stream()
                .filter(t -> "BLOCKED".equals(t.state) && t.lockName != null)
                .map(t -> t.lockName).findFirst().orElse("unknown monitor");

        String severity = blockedPct >= 50 ? AiDiagnosis.SEV_CRITICAL
                        : blockedPct >= 35 ? AiDiagnosis.SEV_HIGH
                        : AiDiagnosis.SEV_MEDIUM;

        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "Lock Contention";
        d.severity       = severity;
        d.confidence     = confidence;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = List.of(
                "blockedThreads=" + blockedCount,
                "blockedPct=" + String.format("%.0f%%", blockedPct),
                "latencyMs=" + String.format("%.2f", latency),
                "spinDelta=" + (long) spinDelta,
                "topLock=" + topLock);
        d.rootCause      = blockedCount + " threads (" + String.format("%.0f", blockedPct) +
                           "% of pool) BLOCKED on " + topLock + ". Latency: " +
                           String.format("%.2f", latency) + " ms. Spin rate: " + (long) spinDelta + "/tick.";
        d.recommendation = "Replace contended synchronized region with LMAX Disruptor. " +
                           "Use ReentrantLock.tryLock(50, MILLISECONDS) and partition work.";
        return d;
    }

    // ── Rule 3: Consumer Backpressure ────────────────────────────────

    private AiDiagnosis evaluateConsumerBackpressure(ThreadMetricsSnapshot snap) {
        if (snap.disruptor == null) return null;
        int    qDepth  = snap.disruptor.queueDepth;
        long   tp      = snap.disruptor.eventsPerSec;
        double latency = snap.disruptor.latencyMs;

        boolean deepQueue      = qDepth > 200;
        boolean risingQueue    = wQueueDepth.isTrendingUp(0.20);
        boolean fallingTp      = wThroughput.isTrendingDown(0.15) && tp > 0;
        boolean sustainedQueue = wQueueDepth.sustainedFraction(150) >= 0.5;

        int sig = (deepQueue ? 2 : 0) + (risingQueue ? 2 : 0) + (fallingTp ? 2 : 0) + (sustainedQueue ? 1 : 0);
        if (sig < 3) return null;

        double cBase = MetricWindow.clamp01(qDepth / 800.0) * 35
                     + (risingQueue ? 20 : 0) + (fallingTp ? 20 : 0)
                     + MetricWindow.clamp01(latency / 10.0) * 15
                     + wQueueDepth.sustainedFraction(150) * 10;
        int confidence = (int) Math.round(clampConf(cBase));

        String severity = qDepth > 700 ? AiDiagnosis.SEV_CRITICAL
                        : qDepth > 400 ? AiDiagnosis.SEV_HIGH
                        : AiDiagnosis.SEV_MEDIUM;

        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "Consumer Backpressure";
        d.severity       = severity;
        d.confidence     = confidence;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = List.of("queueDepth=" + qDepth, "throughput=" + tp + "/s",
                "latencyMs=" + String.format("%.2f", latency),
                "queueTrend=" + (risingQueue ? "↑ rising" : "stable"),
                "throughputTrend=" + (fallingTp ? "↓ falling" : "stable"));
        d.rootCause      = "RingBuffer queue depth: " + qDepth + " (" + (risingQueue ? "rising" : "sustained") +
                           "). Consumers processing slower than producers. Throughput: " + tp + "/s.";
        d.recommendation = "Add more BatchEventProcessor consumers. Increase consumer batch size. " +
                           "Profile slowest event handler.";
        return d;
    }

    // ── Rule 4: CPU Saturation ───────────────────────────────────────

    private AiDiagnosis evaluateCpuSaturation(ThreadMetricsSnapshot snap) {
        double avgCpu     = wCpuAvg.latest();
        double blockedPct = wBlockedPct.latest();
        double spinDelta  = wSpinDelta.latest();

        boolean cpuHigh    = avgCpu >= 60.0;
        boolean notBlocked = blockedPct < 20.0;
        boolean sustained  = wCpuAvg.sustainedFraction(55.0) >= 0.5;
        boolean spinContrib = spinDelta > 20_000;

        // A-02 FIX: both cpuHigh AND sustained are now MANDATORY
        // Prevents CPU Saturation firing during Spring Boot startup (transient spike)
        // or during the first few seconds after chaos activation
        if (!cpuHigh || !sustained) return null;
        int sig = 2 + (notBlocked ? 1 : 0) + 1 + (spinContrib ? 1 : 0); // sustained always true here
        if (sig < 3) return null;

        double cBase = MetricWindow.clamp01(avgCpu / 90.0) * 40
                     + wCpuAvg.sustainedFraction(55.0) * 30
                     + (notBlocked ? 15 : 0)
                     + MetricWindow.clamp01(spinDelta / 100_000.0) * 10;
        int confidence = (int) Math.round(clampConf(cBase));

        String severity = avgCpu >= 85 ? AiDiagnosis.SEV_CRITICAL
                        : avgCpu >= 72 ? AiDiagnosis.SEV_HIGH
                        : AiDiagnosis.SEV_MEDIUM;

        String hottestThread = snap.threads == null ? "unknown" : snap.threads.stream()
                .max(Comparator.comparingDouble(t -> t.cpuPercent))
                .map(t -> t.name + " (" + String.format("%.0f%%", t.cpuPercent) + ")")
                .orElse("unknown");

        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "CPU Saturation";
        d.severity       = severity;
        d.confidence     = confidence;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = List.of(
                "avgCpuPct=" + String.format("%.1f%%", avgCpu),
                "blockedPct=" + String.format("%.0f%%", blockedPct),
                "hottestThread=" + hottestThread,
                "spinDelta=" + (long) spinDelta,
                "sustained=" + (wCpuAvg.sustainedFraction(55) >= 0.5 ? "yes" : "no"));
        d.rootCause      = "Average CPU: " + String.format("%.1f", avgCpu) +
                           "% (hottest: " + hottestThread + "). Only " +
                           String.format("%.0f", blockedPct) + "% blocked — pure computational saturation.";
        d.recommendation = "Profile hot methods with async-profiler or JFR. " +
                           "Reduce allocation in hot loops. Offload to async executor.";
        return d;
    }

    // ── Rule 5: Memory Pressure ──────────────────────────────────────

    private AiDiagnosis evaluateMemoryPressure(ThreadMetricsSnapshot snap) {
        if (snap.jvm == null || snap.jvm.heapMaxMb <= 0) return null;
        double heapPct = wHeapPct.latest();
        long   gcTime  = snap.jvm.gcTimeMs;
        long   gcCount = snap.jvm.gcCount;

        boolean heapHigh   = heapPct >= 78.0;
        boolean gcPressure = wGcTimeMs.isTrendingUp(0.10) || gcTime > 500;
        boolean sustained  = wHeapPct.sustainedFraction(75.0) >= 0.5;

        int sig = (heapHigh ? 2 : 0) + (gcPressure ? 2 : 0) + (sustained ? 1 : 0);
        if (sig < 3) return null;

        double cBase = MetricWindow.clamp01((heapPct - 70) / 25.0) * 40
                     + wHeapPct.sustainedFraction(75.0) * 25
                     + (gcPressure ? 20 : 0)
                     + MetricWindow.clamp01(gcTime / 2000.0) * 10;
        int confidence = (int) Math.round(clampConf(cBase));

        String severity = heapPct >= 92 ? AiDiagnosis.SEV_CRITICAL
                        : heapPct >= 85 ? AiDiagnosis.SEV_HIGH
                        : AiDiagnosis.SEV_MEDIUM;

        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "Memory Pressure";
        d.severity       = severity;
        d.confidence     = confidence;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = List.of(
                "heapUsed=" + snap.jvm.heapUsedMb + "MB",
                "heapMax=" + snap.jvm.heapMaxMb + "MB",
                "heapPct=" + String.format("%.0f%%", heapPct),
                "gcCount=" + gcCount,
                "gcTimeMs=" + gcTime);
        d.rootCause      = "Heap: " + String.format("%.0f", heapPct) + "% (" +
                           snap.jvm.heapUsedMb + "MB / " + snap.jvm.heapMaxMb + "MB). " +
                           "GC ran " + gcCount + " times (" + gcTime + "ms total).";
        d.recommendation = "Increase heap: -Xmx2g. Profile allocations with JFR. " +
                           "Enable object pooling in hot paths. Switch to ZGC: -XX:+UseZGC.";
        return d;
    }

    // ── Rule 6: Healthy ──────────────────────────────────────────────

    private AiDiagnosis evaluateHealthy() {
        double blockedPct = wBlockedPct.latest();
        double heapPct    = wHeapPct.latest();
        double latency    = wLatency.latest();

        double healthScore = MetricWindow.clamp01(1.0 - blockedPct / 30.0) * 40
                           + MetricWindow.clamp01(1.0 - latency    / 2.0)  * 35
                           + MetricWindow.clamp01(1.0 - heapPct    / 90.0) * 25;
        int confidence = (int) Math.round(clampConf(healthScore));
        // A-03 FIX: cap confidence at 75 when windows lack sufficient data
        // Prevents "Healthy 99%" being reported on zero-filled windows (warmup period)
        if (wBlockedPct.size() < WARMUP_REQUIRED) confidence = Math.min(confidence, 75);

        AiDiagnosis d = new AiDiagnosis();
        d.issue          = "Healthy";
        d.severity       = AiDiagnosis.SEV_HEALTHY;
        d.confidence     = confidence;
        d.timestamp      = System.currentTimeMillis();
        d.signals        = List.of(
                "blockedPct=" + String.format("%.0f%%", blockedPct),
                "latencyMs=" + String.format("%.2f", latency),
                "heapPct=" + String.format("%.0f%%", heapPct));
        d.rootCause      = "All signals nominal. Blocked: " + String.format("%.0f", blockedPct) +
                           "%, latency: " + String.format("%.2f", latency) +
                           "ms, heap: " + String.format("%.0f", heapPct) + "%.";
        d.recommendation = "System healthy. Enable CHAOS mode to stress-test resilience.";
        return d;
    }

    // ── History ──────────────────────────────────────────────────────

    private void updateHistory(AiDiagnosis d) {
        long now = d.timestamp;
        boolean issueChanged   = !d.issue.equals(lastIssueName);
        boolean timeoutElapsed = (now - lastIssueTs) > 30_000;
        if (issueChanged || timeoutElapsed) {
            AiDiagnosis.HistoryEntry entry =
                    new AiDiagnosis.HistoryEntry(now, d.issue, d.severity, d.confidence);
            if (history.remainingCapacity() == 0) history.poll();
            history.offer(entry);
            lastIssueName = d.issue;
            lastIssueTs   = now;
        }
    }

    private double clampConf(double raw) {
        double pct = MetricWindow.clamp01(raw / 100.0);
        return 70.0 + pct * 29.0;
    }
}
