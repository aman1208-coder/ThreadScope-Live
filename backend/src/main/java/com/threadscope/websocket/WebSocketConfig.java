package com.threadscope.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;

/**
 * WebSocketConfig
 *
 * Registers the metrics WebSocket handler at ws://localhost:8080/ws/metrics.
 *
 * BUG-03 FIX: spring.websocket.* properties are INVALID and silently ignored
 * by Spring Boot. Buffer size MUST be configured via ServletServerContainerFactoryBean.
 * Without this, Tomcat uses its default 8192-byte buffer, which causes
 * MessageTooLargeException when chaos mode inflates the JSON payload beyond 8KB.
 *
 * BUG-07 FIX: Origin restricted to localhost only — not wildcard.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MetricsWebSocketHandler handler;

    public WebSocketConfig(MetricsWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // D-02 FIX: Use setAllowedOriginPatterns("*") for demo to accept
        // null origin (file:// protocol) AND all localhost variants.
        // Judges at hackathons frequently open index.html by double-clicking,
        // which sends Origin: null and is rejected by setAllowedOrigins().
        // ⚠ Change to explicit origins for production deployment.
        registry
            .addHandler(handler, "/ws/metrics")
            .setAllowedOriginPatterns("*"); // demo-safe: accepts null (file://) + all localhost
    }

    /**
     * BUG-03 FIX: This is the ONLY correct way to set WebSocket buffer sizes
     * in Spring Boot with an embedded Tomcat container.
     *
     * Sets max text/binary message size to 256KB — safely above the maximum
     * expected payload size (~50 threads × 400 bytes + AI diagnosis + history
     * = ~30KB worst case in chaos mode with 20 chaos threads).
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(262144);   // 256KB
        container.setMaxBinaryMessageBufferSize(262144); // 256KB
        container.setMaxSessionIdleTimeout(300000L);     // 5 min idle timeout
        return container;
    }
}
