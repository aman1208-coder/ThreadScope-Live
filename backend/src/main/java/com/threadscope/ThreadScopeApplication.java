package com.threadscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ThreadScope Live — Real JVM Concurrency Debugger
 *
 * Starts a Spring Boot server that:
 *  1. Reads REAL JVM thread data via ThreadMXBean
 *  2. Runs a REAL LMAX Disruptor RingBuffer with worker threads
 *  3. Streams live metrics over WebSocket to the browser dashboard
 *  4. Exposes REST endpoints for control (chaos mode, config)
 */
@SpringBootApplication
@EnableScheduling
public class ThreadScopeApplication {

    public static void main(String[] args) {
        System.out.println("""
            ╔══════════════════════════════════════════════════╗
            ║   THREADSCOPE LIVE  —  Real JVM Agent v2.0.0    ║
            ║   WebSocket  →  ws://localhost:8080/ws/metrics   ║
            ║   Dashboard  →  open frontend/index.html         ║
            ║   REST API   →  http://localhost:8080/api/       ║
            ╚══════════════════════════════════════════════════╝
            """);
        SpringApplication.run(ThreadScopeApplication.class, args);
    }
}
