package com.ratelimiter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    @PostMapping("/action")
    public ResponseEntity<String> action() {
        return ResponseEntity.ok("Request succeeded");
    }
}

