package com.axiom.strategy.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * market-service 의 워치리스트 API 를 strategy-service AdminController 네임스페이스로 프록시.
 *
 * 엔드포인트:
 *   GET    /api/strategy/admin/watchlist
 *   GET    /api/strategy/admin/watchlist/counts
 *   POST   /api/strategy/admin/watchlist/sync
 *   POST   /api/strategy/admin/watchlist/{ticker}/exclude
 *   DELETE /api/strategy/admin/watchlist/{ticker}/exclude
 *   POST   /api/strategy/admin/watchlist/{ticker}/add
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy/admin/watchlist")
@RequiredArgsConstructor
public class WatchlistAdminController {

    @Qualifier("marketWebClient")
    private final WebClient marketWebClient;

    @GetMapping
    public ResponseEntity<Object> list() {
        Object result = marketWebClient.get()
                .uri("/api/watchlist")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/counts")
    public ResponseEntity<Object> counts() {
        Object result = marketWebClient.get()
                .uri("/api/watchlist/counts")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Long>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync")
    public ResponseEntity<Object> sync() {
        Object result = marketWebClient.post()
                .uri("/api/watchlist/sync")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Integer>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/review/daily")
    public ResponseEntity<Object> dailyReview() {
        Object result = marketWebClient.post()
                .uri("/api/watchlist/review/daily")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/review/weekly")
    public ResponseEntity<Object> weeklyReview() {
        Object result = marketWebClient.post()
                .uri("/api/watchlist/review/weekly")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/review/quarterly")
    public ResponseEntity<Object> quarterlyReview() {
        Object result = marketWebClient.post()
                .uri("/api/watchlist/review/quarterly")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{ticker}/exclude")
    public ResponseEntity<Object> exclude(
            @PathVariable String ticker,
            @RequestBody(required = false) Map<String, String> body) {
        Object result = marketWebClient.post()
                .uri("/api/watchlist/{ticker}/exclude", ticker)
                .bodyValue(body != null ? body : Map.of("reason", ""))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{ticker}/exclude")
    public ResponseEntity<Object> restore(@PathVariable String ticker) {
        Object result = marketWebClient.delete()
                .uri("/api/watchlist/{ticker}/exclude", ticker)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{ticker}/add")
    public ResponseEntity<Object> add(
            @PathVariable String ticker,
            @RequestBody(required = false) Map<String, String> body) {
        Object result = marketWebClient.post()
                .uri("/api/watchlist/{ticker}/add", ticker)
                .bodyValue(body != null ? body : Map.of())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        return ResponseEntity.ok(result);
    }
}
