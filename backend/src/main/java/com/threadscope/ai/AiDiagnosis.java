package com.threadscope.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AiDiagnosis
 *
 * The complete AI root cause analysis result attached to every
 * WebSocket payload. Every field is derived from real JVM metrics —
 * no random values, no simulations.
 *
 * JSON shape (sent to browser):
 * {
 *   "issue":          "Lock Contention",
 *   "severity":       "HIGH",
 *   "rootCause":      "7 threads are BLOCKED on ReentrantLock@0x4f2a...",
 *   "recommendation": "Replace synchronized block with Disruptor RingBuffer...",
 *   "confidence":     87,
 *   "timestamp":      1717531200000,
 *   "signals":        ["blockedThreads=7", "latencyMs=4.2", "spinCount=82000"],
 *   "history":        [ { ...last 10 entries... } ]
 * }
 */
public class AiDiagnosis {

    /** Short issue name shown in the card header */
    @JsonProperty("issue")
    public String issue;

    /** HEALTHY | LOW | MEDIUM | HIGH | CRITICAL */
    @JsonProperty("severity")
    public String severity;

    /** Full plain-English explanation of what the metrics indicate */
    @JsonProperty("rootCause")
    public String rootCause;

    /** Concrete, actionable fix recommendation */
    @JsonProperty("recommendation")
    public String recommendation;

    /** 0-100 confidence score calculated from metric signal strength */
    @JsonProperty("confidence")
    public int confidence;

    /** Overall system health 0-100 — shown as the Health Score widget */
    @JsonProperty("healthScore")
    public int healthScore;

    /** Unix epoch ms when this diagnosis was generated */
    @JsonProperty("timestamp")
    public long timestamp;

    /**
     * The specific metric values that drove this diagnosis.
     * e.g. ["blockedThreads=7", "latencyMs=4.2", "heapPct=88%"]
     * Shown in the UI so the user can verify the AI's reasoning.
     */
    @JsonProperty("signals")
    public List<String> signals;

    /**
     * Rolling history of the last 10 diagnoses (oldest first).
     * Each entry is a compact summary for the timeline UI.
     */
    @JsonProperty("history")
    public List<HistoryEntry> history;

    // ── History entry ────────────────────────────────────────────

    public static class HistoryEntry {
        @JsonProperty("timestamp") public long   timestamp;
        @JsonProperty("issue")     public String issue;
        @JsonProperty("severity")  public String severity;
        @JsonProperty("confidence") public int   confidence;

        public HistoryEntry(long ts, String issue, String severity, int confidence) {
            this.timestamp  = ts;
            this.issue      = issue;
            this.severity   = severity;
            this.confidence = confidence;
        }
    }

    // ── Severity constants ───────────────────────────────────────

    public static final String SEV_HEALTHY  = "HEALTHY";
    public static final String SEV_LOW      = "LOW";
    public static final String SEV_MEDIUM   = "MEDIUM";
    public static final String SEV_HIGH     = "HIGH";
    public static final String SEV_CRITICAL = "CRITICAL";
}
