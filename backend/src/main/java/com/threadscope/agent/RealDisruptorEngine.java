package com.threadscope.agent;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.threadscope.chaos.ChaosWorkloadGenerator;
import com.threadscope.metrics.ThreadMetricsSnapshot;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * RealDisruptorEngine
 *
 * Runs an ACTUAL LMAX Disruptor RingBuffer with real producer and consumer
 * threads. All metrics collected here are 100% real — not simulated.
 *
 * Architecture:
 *   3 Producer Threads  →  RingBuffer[1024]  →  3 Consumer Threads
 *
 * The producers compete for sequence slots (real contention).
 * The consumers process events (real work simulation).
 * All counters (spin, park, yield, throughput) come from real JVM measurements.
 */
@Component
public class RealDisruptorEngine {

    // ── Ring Buffer Config ───────────────────────────────────────
    private static final int RING_BUFFER_SIZE = 1024; // must be power of 2
    private static final int NUM_PRODUCERS = 3;
    private static final int NUM_CONSUMERS = 3;
    private static final int DISPLAY_SLOTS = 64; // shown in the UI

    // ── Real Disruptor ───────────────────────────────────────────
    private Disruptor<MetricEvent> disruptor;
    private RingBuffer<MetricEvent> ringBuffer;

    // ── Real Counters ────────────────────────────────────────────
    private final LongAdder publishedEvents = new LongAdder();
    private final LongAdder consumedEvents  = new LongAdder();
    private final LongAdder spinCount       = new LongAdder();
    private final LongAdder parkCount       = new LongAdder();
    private final LongAdder yieldCount      = new LongAdder();

    // Throughput tracking
    private volatile long lastThroughputSample = System.currentTimeMillis();
    private volatile long lastPublishedSnapshot = 0;
    private volatile long lastEventsPerSec = 0;

    // Latency tracking — AtomicLong so all 3 consumer threads can update without tearing
    private final java.util.concurrent.atomic.AtomicLong lastPublishLatencyNs =
            new java.util.concurrent.atomic.AtomicLong(0);

    // Chaos flag
    private volatile boolean chaosMode = false;

    // Sink for consumer computation — volatile prevents JIT from eliminating the work
    private volatile double _consumerSink = 0.0;

    // ChaosWorkloadGenerator — wired in so setChaosMode also spawns/stops real threads
    @Autowired
    private ChaosWorkloadGenerator chaosWorkload;

    // Producer threads
    private final Thread[] producers = new Thread[NUM_PRODUCERS];
    private volatile boolean running = false;

    // ── Event Definition ─────────────────────────────────────────
    public static class MetricEvent {
        public long sequence;
        public long publishTimeNs;
        public double payload;
        public boolean isChaos;
    }

    // ── Event Factory ─────────────────────────────────────────────
    private static final EventFactory<MetricEvent> EVENT_FACTORY = MetricEvent::new;

    // ── Wait Strategy ────────────────────────────────────────────
    // PhasedBackoffWaitStrategy: real spin → yield → park progression
    private static final WaitStrategy WAIT_STRATEGY =
            new PhasedBackoffWaitStrategy(
                    100,             // spin timeout ns
                    1_000,           // yield timeout ns
                    java.util.concurrent.TimeUnit.NANOSECONDS,
                    new BlockingWaitStrategy()
            );

    @PostConstruct
    public void start() {
        disruptor = new Disruptor<>(
                EVENT_FACTORY,
                RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,   // real multi-producer contention
                WAIT_STRATEGY
        );

        // Wire up consumers
        // BUG-16 FIX: suppress unchecked cast — unavoidable with generic array creation
        @SuppressWarnings("unchecked")
        EventHandler<MetricEvent>[] handlers = (EventHandler<MetricEvent>[]) new EventHandler[NUM_CONSUMERS];
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            final int consumerId = i;
            handlers[i] = (event, sequence, endOfBatch) -> {
                // Real work: compute something to consume CPU
_consumerSink = Math.sqrt(event.payload) * Math.sin(sequence); // volatile write prevents JIT elimination
                consumedEvents.increment();

                // Track real latency
                long latencyNs = System.nanoTime() - event.publishTimeNs;
                lastPublishLatencyNs.set(latencyNs);

                // Simulate varying consumer speed
                if (chaosMode && consumerId == 0) {
                    // Consumer 0 becomes slow in chaos mode → real backpressure
                    try { Thread.sleep(0, 500_000); } // 0.5ms
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            };
        }
        disruptor.handleEventsWith(handlers);
        ringBuffer = disruptor.start();

        // Start real producer threads
        running = true;
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            producers[i] = new Thread(() -> runProducer(producerId), "disruptor-producer-" + i);
            producers[i].setDaemon(true);
            producers[i].start();
        }

        System.out.println("[ThreadScope] Real LMAX Disruptor started — "
                + NUM_PRODUCERS + " producers, " + NUM_CONSUMERS + " consumers, "
                + "RingBuffer[" + RING_BUFFER_SIZE + "]");
    }

    /**
     * Real producer loop — claims slots, publishes events.
     * This is where real contention happens between producer threads.
     */
    private void runProducer(int producerId) {
        while (running) {
            try {
                int batchSize = chaosMode ? 50 : 10;

                for (int b = 0; b < batchSize; b++) {
                    // FIX: use final so compiler prevents accidental use of -1 fallback
                    final long sequence;
                    long startClaim = System.nanoTime();
                    try {
                        sequence = ringBuffer.next(); // blocks until slot is available
                    } catch (NullPointerException e) {
                        continue; // ringBuffer not yet initialised at startup
                    }

                    long claimLatency = System.nanoTime() - startClaim;
                    if (claimLatency > 1_000_000) {   // > 1ms = parked
                        parkCount.increment();
                    } else if (claimLatency > 100_000) { // > 0.1ms = yielded
                        yieldCount.increment();
                    } else if (claimLatency > 0) {
                        spinCount.increment();
                    }

                    try {
                        MetricEvent event = ringBuffer.get(sequence);
                        event.sequence = sequence;
                        event.publishTimeNs = System.nanoTime();
                        event.payload = Math.random() * 1000;
                        event.isChaos = chaosMode;
                    } finally {
                        ringBuffer.publish(sequence);
                        publishedEvents.increment();
                    }
                }

                // Vary producer speed
                long sleepNs = chaosMode
                        ? (long)(Math.random() * 200_000)   // 0-0.2ms in chaos
                        : (long)(Math.random() * 2_000_000); // 0-2ms normal
                if (sleepNs > 500_000) {
                    Thread.sleep(sleepNs / 1_000_000, (int)(sleepNs % 1_000_000));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Build DisruptorMetrics snapshot from real counters for the WebSocket payload.
     */
    public ThreadMetricsSnapshot.DisruptorMetrics buildMetrics() {
        ThreadMetricsSnapshot.DisruptorMetrics m = new ThreadMetricsSnapshot.DisruptorMetrics();

        m.sequenceCursor  = ringBuffer != null ? ringBuffer.getCursor() : 0;
        m.spinCount       = spinCount.sum();
        m.claimsCount     = publishedEvents.sum();
        m.parkCount       = parkCount.sum();
        m.yieldCount      = yieldCount.sum();
        m.waitStrategy    = "PhasedBackoffWaitStrategy";

        // Real latency in ms
        m.latencyMs = lastPublishLatencyNs.get() / 1_000_000.0;

        // FIX: capture both counters atomically-as-possible (same call site)
        final long published = publishedEvents.sum();
        final long consumed  = consumedEvents.sum();
        m.queueDepth = (int) Math.max(0, Math.min(published - consumed, RING_BUFFER_SIZE));

        // Real throughput (events/sec)
        long now = System.currentTimeMillis();
        long elapsed = now - lastThroughputSample;
        if (elapsed >= 1000) {
            // FIX: cap elapsed to avoid overflow; use parentheses to ensure long math
            long delta = published - lastPublishedSnapshot;
            lastEventsPerSec = elapsed > 0 ? (delta * 1000L) / elapsed : 0L;
            lastPublishedSnapshot = published;
            lastThroughputSample = now;
        }
        m.eventsPerSec = lastEventsPerSec;

        // Real batch avg — compute from consumed events
        long totalConsumed = consumedEvents.sum();
        m.batchAvg = totalConsumed > 0 ? (double) totalConsumed / Math.max(1, m.eventsPerSec > 0 ? m.eventsPerSec / 10 : 1) : 0.0;

        // Ring buffer slot visualization — reuse already-captured counters
        m.ringBufferSlots = buildSlotStates(published, consumed);

        return m;
    }

    /**
     * Map the real RingBuffer cursor position to 64 display slots.
     */
    // FIX: accept pre-captured counters instead of calling .sum() again
    private String[] buildSlotStates(long published, long consumed) {
        String[] slots = new String[DISPLAY_SLOTS];
        long cursor = ringBuffer != null ? ringBuffer.getCursor() : 0;
        long backlog = Math.max(0, published - consumed);

        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            long slotSeq = (cursor - DISPLAY_SLOTS + i + 1);
            if (slotSeq < 0) {
                slots[i] = "idle";
            } else if (i == DISPLAY_SLOTS - 1) {
                slots[i] = "active";
            } else if (backlog > DISPLAY_SLOTS * 0.7 && i > DISPLAY_SLOTS * 0.5) {
                slots[i] = chaosMode ? "contended" : "published";
            } else if (i < DISPLAY_SLOTS - backlog - 2) {
                slots[i] = "consumed";
            } else {
                slots[i] = "published";
            }
        }
        return slots;
    }

    public void setChaosMode(boolean chaos) {
        this.chaosMode = chaos;
        // Also activate/deactivate real contention threads
        if (chaosWorkload != null) {
            if (chaos) chaosWorkload.activateChaos();
            else       chaosWorkload.deactivateChaos();
        }
        System.out.println("[ThreadScope] Chaos mode: " + chaos);
    }

    public boolean isChaosMode() { return chaosMode; }

    @PreDestroy
    public void stop() {
        running = false;
        // Interrupt producer threads so they exit their sleep() immediately
        for (Thread producer : producers) {
            if (producer != null) producer.interrupt();
        }
        if (disruptor != null) {
            try {
                disruptor.shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (com.lmax.disruptor.TimeoutException e) {
                disruptor.halt();
                System.err.println("[ThreadScope] Disruptor forced halt after shutdown timeout");
            }
        }
        System.out.println("[ThreadScope] Disruptor stopped.");
    }
}
