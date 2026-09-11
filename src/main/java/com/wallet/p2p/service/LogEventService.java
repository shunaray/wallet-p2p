package com.wallet.p2p.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class LogEventService {

    private static final int MAX_BUFFER_SIZE = 500;
    private final ConcurrentLinkedDeque<Map<String, Object>> logBuffer = new ConcurrentLinkedDeque<>();

    public void recordDomainEvent(String eventType, Map<String, Object> details) {
        details.put("event_type", eventType);
        details.put("timestamp", Instant.now().toString());

        logBuffer.addFirst(details);
        while (logBuffer.size() > MAX_BUFFER_SIZE) {
            logBuffer.pollLast();
        }
    }

    public List<Map<String, Object>> getRecentLogs() {
        return new ArrayList<>(logBuffer);
    }
}
