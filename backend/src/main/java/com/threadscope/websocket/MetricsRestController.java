package com.threadscope.websocket;

import com.threadscope.agent.RealDisruptorEngine;
import com.threadscope.ai.AiDiagnosis;
import com.threadscope.metrics.MetricsCollector;
import com.threadscope.metrics.ThreadMetricsSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MetricsRestController
 *
 * GET /api/status — server status + connected clients GET /api/snapshot — full
 * current metrics snapshot GET /api/threads — thread list only GET /api/ai —
 * current AI diagnosis only POST /api/chaos?on=true — toggle chaos mode
 */
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {
    "http://localhost:3000", "http://127.0.0.1:3000",
    "http://localhost:5500", "http://127.0.0.1:5500"
})
public class MetricsRestController {

    @Value("${APP_BASE_URL:}")
    private String appBaseUrlEnv;
    @Value("${WS_BASE_URL:}")
    private String wsBaseUrlEnv;

    @Autowired
    private MetricsCollector collector;
    @Autowired
    private RealDisruptorEngine disruptor;
    @Autowired
    private MetricsWebSocketHandler wsHandler;

    // S-02 FIX: simple token-bucket rate limiter for /api/chaos
    // Prevents 100 rapid toggles/sec from spawning/destroying 800 threads/sec
    private final AtomicLong lastChaosToggleMs = new AtomicLong(0);

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        String appBase = resolveAppBaseUrl();
        String wsBase = resolveWsBaseUrl(appBase);
        return ResponseEntity.ok(Map.of(
                "status", "running",
                "connectedClients", wsHandler.connectedClients(),
                "chaosMode", disruptor.isChaosMode(),
                "wsUrl", wsBase + "/ws/metrics",
                "apiUrl", appBase + "/api",
                "version", "2.0.0"
        ));
    }

    @GetMapping("/snapshot")
    public ResponseEntity<ThreadMetricsSnapshot> snapshot() {
        return ResponseEntity.ok(collector.collect());
    }

    @GetMapping("/threads")
    public ResponseEntity<?> threads() {
        // collect() once — do not call it twice
        ThreadMetricsSnapshot snap = collector.collect();
        return ResponseEntity.ok(snap.threads);
    }

    private String resolveAppBaseUrl() {
        String baseUrl = nonEmpty(appBaseUrlEnv);
        if (baseUrl.isBlank()) {
            baseUrl = nonEmpty(System.getenv("APP_BASE_URL"));
        }
        if (baseUrl.isBlank()) {
            baseUrl = nonEmpty(System.getenv("RENDER_EXTERNAL_URL"));
        }
        if (baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        return stripTrailingSlash(baseUrl);
    }

    private String resolveWsBaseUrl(String appBaseUrl) {
        String wsUrl = nonEmpty(wsBaseUrlEnv);
        if (wsUrl.isBlank()) {
            wsUrl = nonEmpty(System.getenv("WS_BASE_URL"));
        }
        if (wsUrl.isBlank()) {
            wsUrl = deriveWsBaseUrlFromApp(appBaseUrl);
        }
        return stripTrailingSlash(wsUrl);
    }

    private String deriveWsBaseUrlFromApp(String appBaseUrl) {
        if (appBaseUrl.startsWith("https://")) {
            return "wss://" + appBaseUrl.substring(8);
        }
        if (appBaseUrl.startsWith("http://")) {
            return "ws://" + appBaseUrl.substring(7);
        }
        return appBaseUrl;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String nonEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    @GetMapping("/ai")
    public ResponseEntity<AiDiagnosis> aiDiagnosis() {
        ThreadMetricsSnapshot snap = collector.collect();
        if (snap.aiDiagnosis == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(snap.aiDiagnosis);
    }

    @PostMapping("/chaos")
    public ResponseEntity<Map<String, Object>> chaos(@RequestParam boolean on) {
        // S-02 FIX: enforce 500ms minimum between chaos toggles
        long now = System.currentTimeMillis();
        long last = lastChaosToggleMs.get();
        if (now - last < 500) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Rate limited — wait 500ms between chaos toggles",
                    "retryAfterMs", 500 - (now - last)
            ));
        }
        lastChaosToggleMs.set(now);
        disruptor.setChaosMode(on);
        return ResponseEntity.ok(Map.of(
                "chaosMode", on,
                "message", on ? "Chaos mode ACTIVATED — brace for impact" : "Chaos mode deactivated"
        ));
    }
}
