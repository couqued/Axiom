package com.axiom.market.controller;

import com.axiom.market.service.KisTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalTokenController {

    private final KisTokenService kisTokenService;

    /**
     * Access Token 반환.
     * @param mode "paper" | "real" — real 요청 시 real 전용 토큰 반환 (미설정이면 null)
     */
    @GetMapping("/token")
    public Map<String, String> getToken(@RequestParam(required = false) String mode) {
        String token;
        if ("real".equals(mode)) {
            token = kisTokenService.getRealAccessToken();
        } else {
            token = kisTokenService.getAccessToken();
        }
        return Map.of("token", token != null ? token : "");
    }
}
