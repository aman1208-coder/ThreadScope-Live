package com.threadscope.websocket;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * RootController
 *
 * Exposes a basic root endpoint so the backend returns a valid response at /
 * instead of the default Whitelabel 404 page.
 */
@Controller
public class RootController {

    @GetMapping("/")
    @ResponseBody
    public Map<String, Object> root() {
        return Map.of(
                "status", "running",
                "message", "ThreadScope Live backend is running",
                "api", "/api/status"
        );
    }
}
