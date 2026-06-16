package com.threadscope.chaos;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ChaosWorkloadGenerator
 *
 * When chaos mode is enabled, this spawns real threads that fight over
 * real locks, creating genuine BLOCKED threads visible in ThreadMXBean.
 *
 * This is what makes chaos mode show REAL contention, not fake numbers.
 *
 * Workloads:
 *  - LockContentionWorker:  threads compete for a shared ReentrantLock
 *  - CPUSpinWorker:         threads spin-compute to burn CPU
 *  - WaitingWorker:         threads call Object.wait() → WAITING state
 */
@Component
public class ChaosWorkloadGenerator {

    private volatile boolean chaosActive = false;
    private final List<Thread> chaosThreads = new ArrayList<>();

    // Shared lock that chaos threads fight over (real contention)
    private final ReentrantLock sharedLock = new ReentrantLock(true);
    private final Object waitMonitor = new Object();
    // Volatile sink prevents JIT from eliminating CPU spin work
    private volatile double _cpuSink = 0.0;

    public synchronized void activateChaos() {
        if (chaosActive) return;
        chaosActive = true;
        System.out.println("[ThreadScope] Activating chaos workload — spawning contention threads");

        // 4 threads fighting for 1 lock → real BLOCKED threads
        for (int i = 0; i < 4; i++) {
            final int id = i;
            Thread t = new Thread(() -> lockContentionWorker(id), "chaos-lock-" + i);
            t.setDaemon(true);
            chaosThreads.add(t);
            t.start();
        }

        // 2 threads burning CPU → real RUNNABLE with high CPU%
        for (int i = 0; i < 2; i++) {
            final int id = i;
            Thread t = new Thread(() -> cpuSpinWorker(id), "chaos-cpu-" + i);
            t.setDaemon(true);
            chaosThreads.add(t);
            t.start();
        }

        // 2 threads waiting → real WAITING threads
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(this::waitingWorker, "chaos-wait-" + i);
            t.setDaemon(true);
            chaosThreads.add(t);
            t.start();
        }

        // X-01 FIX: check chaosActive again after spawning — shutdown may have raced us
        // If PreDestroy fired between chaosActive=true and now, interrupt all threads
        if (!chaosActive) {
            chaosThreads.forEach(Thread::interrupt);
            chaosThreads.clear();
            System.out.println("[ThreadScope] Chaos: activation aborted — shutdown raced start");
            return;
        }
        System.out.println("[ThreadScope] Chaos: " + chaosThreads.size() + " threads spawned");
    }

    public synchronized void deactivateChaos() {
        if (!chaosActive) return;
        // FIX: interrupt threads BEFORE setting chaosActive=false so workers
        // receive interrupt signal while still in their wait/sleep calls.
        // Setting chaosActive=false first creates a window where the loop
        // condition is true but the thread hasn't been interrupted yet.
        chaosThreads.forEach(Thread::interrupt);
        synchronized (waitMonitor) { waitMonitor.notifyAll(); }
        chaosActive = false; // set AFTER interrupt so workers see it correctly
        chaosThreads.clear();
        System.out.println("[ThreadScope] Chaos deactivated — threads stopped");
    }

    // ── Worker implementations ───────────────────────────────────

    private void lockContentionWorker(int id) {
        while (chaosActive && !Thread.currentThread().isInterrupted()) {
            try {
                sharedLock.lockInterruptibly(); // blocks → BLOCKED state visible in JVM
                try {
                    // Hold lock briefly while computing
                    double _r = 0;
                    for (int i = 0; i < 50_000; i++) _r += Math.sqrt(i);
                    _cpuSink = _r; // volatile write prevents JIT elimination
                    Thread.sleep(1 + (long)(Math.random() * 5));
                } finally {
                    sharedLock.unlock();
                }
                Thread.sleep((long)(Math.random() * 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void cpuSpinWorker(int id) {
        while (chaosActive && !Thread.currentThread().isInterrupted()) {
            // Real CPU spin — shows as RUNNABLE + high CPU% in ThreadMXBean
            double _r = 0;
            for (int i = 0; i < 500_000; i++) {
                _r += Math.sin(i) * Math.cos(i);
            }
            _cpuSink = _r; // volatile write prevents JIT elimination
            // X-02 FIX: 1ms sleep prevents complete scheduler starvation on 2-core VMs
            // Without this, 2 spin workers occupy both cores and the @Scheduled
            // broadcast thread cannot run, freezing the dashboard.
            try { Thread.sleep(1); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }

    private void waitingWorker() {
        while (chaosActive && !Thread.currentThread().isInterrupted()) {
            try {
                synchronized (waitMonitor) {
                    waitMonitor.wait(200); // → TIMED_WAITING state
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public boolean isChaosActive() { return chaosActive; }

    @PreDestroy
    public void shutdown() { deactivateChaos(); }
}
