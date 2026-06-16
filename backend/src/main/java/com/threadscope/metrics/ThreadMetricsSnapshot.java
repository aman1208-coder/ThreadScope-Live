package com.threadscope.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.threadscope.ai.AiDiagnosis;
import java.util.List;

/**
 * The exact JSON payload sent to the browser dashboard over WebSocket.
 * Every field maps 1-to-1 to what the frontend JS reads.
 */
public class ThreadMetricsSnapshot {

    @JsonProperty("timestamp")
    public long timestamp;

    @JsonProperty("threads")
    public List<ThreadInfo> threads;

    @JsonProperty("disruptor")
    public DisruptorMetrics disruptor;

    @JsonProperty("jvm")
    public JvmMetrics jvm;

    @JsonProperty("hotMethods")
    public List<HotMethod> hotMethods;

    @JsonProperty("isChaos")
    public boolean isChaos;

    /** AI Root Cause Analysis — populated by AiRootCauseEngine on every tick */
    @JsonProperty("aiDiagnosis")
    public AiDiagnosis aiDiagnosis;

    // ── Thread Info ──────────────────────────────────────────────
    public static class ThreadInfo {
        @JsonProperty("id")       public long id;
        @JsonProperty("name")     public String name;
        @JsonProperty("state")    public String state;        // RUNNABLE, BLOCKED, WAITING, TIMED_WAITING
        @JsonProperty("cpu")      public double cpuPercent;   // 0-100
        @JsonProperty("lock")     public String lockName;     // null if not blocked
        @JsonProperty("stackDepth") public int stackDepth;
        @JsonProperty("method")   public String topMethod;    // top of stack
        @JsonProperty("blockedCount") public long blockedCount;
        @JsonProperty("waitedCount")  public long waitedCount;
        @JsonProperty("blockedTime")  public long blockedTimeMs;
    }

    // ── Disruptor Ring Buffer Metrics ────────────────────────────
    public static class DisruptorMetrics {
        @JsonProperty("sequenceCursor")  public long sequenceCursor;
        @JsonProperty("spinCount")       public long spinCount;
        @JsonProperty("claimsCount")     public long claimsCount;
        @JsonProperty("parkCount")       public long parkCount;
        @JsonProperty("yieldCount")      public long yieldCount;
        @JsonProperty("batchAvg")        public double batchAvg;
        @JsonProperty("ringBufferSlots") public String[] ringBufferSlots; // 64 slot states
        @JsonProperty("throughput")      public long eventsPerSec;
        @JsonProperty("latencyMs")       public double latencyMs;
        @JsonProperty("queueDepth")      public int queueDepth;
        @JsonProperty("waitStrategy")    public String waitStrategy;
    }

    // ── JVM-wide Metrics ─────────────────────────────────────────
    public static class JvmMetrics {
        @JsonProperty("heapUsedMb")    public long heapUsedMb;
        @JsonProperty("heapMaxMb")     public long heapMaxMb;
        @JsonProperty("gcCount")       public long gcCount;
        @JsonProperty("gcTimeMs")      public long gcTimeMs;
        @JsonProperty("totalThreads")  public int totalThreads;
        @JsonProperty("daemonThreads") public int daemonThreads;
        @JsonProperty("deadlocked")    public long[] deadlockedThreadIds; // null = no deadlock
        @JsonProperty("uptime")        public long uptimeSeconds;
    }

    // ── Hot Methods (real stack sampling) ────────────────────────
    public static class HotMethod {
        @JsonProperty("name")    public String name;
        @JsonProperty("samples") public int samples;
    }
}
