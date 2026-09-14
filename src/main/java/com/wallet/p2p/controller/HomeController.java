package com.wallet.p2p.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HomeController {

    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Map<String, Object>> root() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "Wallet & P2P Transfer Microservice");
        response.put("status", "UP");

        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("health", "/actuator/health");
        endpoints.put("metrics", "/actuator/prometheus");
        endpoints.put("logs", "/logs");
        endpoints.put("wallets", "/wallets");
        endpoints.put("transfers", "/transfers");
        response.put("endpoints", endpoints);

        return ResponseEntity.ok(response);
    }
}
