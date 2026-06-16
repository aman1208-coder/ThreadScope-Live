package com.threadscope.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threadscope.metrics.MetricsCollector;
import com.threadscope.metrics.ThreadMetricsSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetricsWebSocketHandler
 *
 * Manages all connected browser clients and broadcasts real JVM metrics
 * at 10fps (every 100ms) to every open WebSocket connection.
 *
 * Protocol (server → browser):
 *   JSON text frame containing ThreadMetricsSnapshot
 *
 * Protocol (browser → server):
 *   "chaos:true"  / "chaos:false"   — toggle chaos mode
 *   "ping"                          — keepalive
 */
@Component
public class MetricsWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    // Reused dead-session set — avoids one ConcurrentHashMap allocation per broadcast tick (BUG-15 fix)
    private final Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
    private final MetricsCollector collector;
    private final ObjectMapper objectMapper;

    public MetricsWebSocketHandler(MetricsCollector collector) {
        this.collector    = collector;
        this.objectMapper = new ObjectMapper();
    }

    // ── Connection Lifecycle ─────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        // S-01: Log origin for security audit trail — helps detect unauthorized connections
        String origin = session.getHandshakeHeaders().getFirst("Origin");
        System.out.println("[ThreadScope] Browser connected: " + session.getId()
                + " origin=" + origin + " (total: " + sessions.size() + ")");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("[ThreadScope] Browser disconnected: " + session.getId());
    }

    // ── Incoming messages from browser ──────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload().trim();
        // FIX: reject oversized payloads before any processing (DoS guard)
        if (payload.length() > 64) {
            System.out.println("[ThreadScope] Rejected oversized message (" + payload.length() + " bytes)");
            return;
        }
        System.out.println("[ThreadScope] Received: " + payload);

        switch (payload) {
            case "chaos:true"  -> collector.disruptor().setChaosMode(true);
            case "chaos:false" -> collector.disruptor().setChaosMode(false);
            case "ping"        -> sendPong(session);
            default            -> { /* ignore unknown — do not log unknown content */ }
        }
    }

    private void sendPong(WebSocketSession session) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            }
        } catch (IOException e) {
            // ignore
        }
    }

    // ── Broadcast loop — fires every 100ms ──────────────────────

    // C-03 FIX: fixedDelay=100 (not fixedRate) so if a tick takes >100ms
    // (e.g. dumpAllThreads safepoint + AI analysis + N sends), the next tick
    // starts 100ms AFTER the current one finishes — no pile-up on slow machines.
    @Scheduled(fixedDelay = 100)
    public void broadcastMetrics() {
        if (sessions.isEmpty()) return;

        try {
            ThreadMetricsSnapshot snapshot = collector.collect();
            String json = objectMapper.writeValueAsString(snapshot);
            TextMessage msg = new TextMessage(json);

            // Send to all connected sessions
            deadSessions.clear();
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    deadSessions.add(session);
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(msg);
                    }
                } catch (IOException e) {
                    deadSessions.add(session);
                }
            }
            sessions.removeAll(deadSessions);

        } catch (Exception e) {
            // FIX: log class name so NPE vs IOException vs JsonMappingException is visible
            System.err.println("[ThreadScope] Broadcast error [" + e.getClass().getSimpleName() + "]: " + e.getMessage());
            if (e.getCause() != null) System.err.println("  Caused by: " + e.getCause());
        }
    }

    public int connectedClients() { return sessions.size(); }
}
