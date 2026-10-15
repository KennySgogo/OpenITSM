package com.openitsm.controller;

import com.openitsm.service.HealthService;
import com.openitsm.vo.HealthVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    private final HealthService service;
    public HealthController(HealthService service) { this.service = service; }
    @GetMapping("/health/live") public HealthVO live() { return new HealthVO("UP"); }
    @GetMapping("/health/ready") public ResponseEntity<HealthVO> ready() {
        boolean ready = service.ready();
        return ResponseEntity.status(ready ? 200 : 503).body(new HealthVO(ready ? "UP" : "DOWN"));
    }
}
