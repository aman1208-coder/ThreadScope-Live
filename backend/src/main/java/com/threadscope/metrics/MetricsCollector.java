package com.threadscope.metrics;

import com.threadscope.agent.RealDisruptorEngine;
import com.threadscope.agent.RealThreadInspector;
import com.threadscope.ai.AiRootCauseEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MetricsCollector {

    @Autowired private RealThreadInspector inspector;
    @Autowired private RealDisruptorEngine  disruptorEngine;
    @Autowired private AiRootCauseEngine    aiEngine;

    public ThreadMetricsSnapshot collect() {
        ThreadMetricsSnapshot snap = new ThreadMetricsSnapshot();
        snap.timestamp = System.currentTimeMillis();
        snap.isChaos   = disruptorEngine.isChaosMode();
        snap.threads   = inspector.collectThreads();
        snap.disruptor = disruptorEngine.buildMetrics();

        ThreadMetricsSnapshot.JvmMetrics jvm = new ThreadMetricsSnapshot.JvmMetrics();
        jvm.heapUsedMb          = inspector.heapUsedMb();
        jvm.heapMaxMb           = inspector.heapMaxMb();
        jvm.gcCount             = inspector.totalGcCount();
        jvm.gcTimeMs            = inspector.totalGcTimeMs();
        jvm.totalThreads        = inspector.totalThreadCount();
        jvm.daemonThreads       = inspector.daemonThreadCount();
        jvm.deadlockedThreadIds = inspector.findDeadlockedThreads();
        jvm.uptimeSeconds       = inspector.uptimeSeconds();
        snap.jvm = jvm;

        snap.hotMethods  = inspector.getHotMethods(6);

        // ── AI Root Cause Analysis (uses real metrics above — no simulation) ──
        snap.aiDiagnosis = aiEngine.analyse(snap);

        return snap;
    }

    public RealDisruptorEngine disruptor() { return disruptorEngine; }
}
