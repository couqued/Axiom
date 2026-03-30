package com.axiom.market.controller;

import com.axiom.market.service.KisTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalTokenController {

    private final KisTokenService kisTokenService;

    @GetMapping("/token")
    public Map<String, String> getToken() {
        String token = kisTokenService.getAccessToken();
        return Map.of("token", token != null ? token : "");
    }

    @PostMapping("/token/refresh")
    public Map<String, String> refreshToken() {
        kisTokenService.forceRefresh();
        String token = kisTokenService.getAccessToken();
        return Map.of("token", token != null ? token : "");
    }
}
