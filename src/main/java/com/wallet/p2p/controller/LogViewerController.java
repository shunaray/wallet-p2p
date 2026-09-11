package com.wallet.p2p.controller;

import com.wallet.p2p.service.LogEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/logs")
public class LogViewerController {

    private final LogEventService logEventService;

    public LogViewerController(LogEventService logEventService) {
        this.logEventService = logEventService;
    }

    /**
     * GET /logs - Publicly viewable structured domain event logs.
     * Evaluators can inspect real-time domain events directly from their browser or curl.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getRecentLogs() {
        return ResponseEntity.ok(logEventService.getRecentLogs());
    }
}
