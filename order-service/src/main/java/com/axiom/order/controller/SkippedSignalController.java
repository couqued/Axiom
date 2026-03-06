package com.axiom.order.controller;

import com.axiom.order.dto.SkippedSignalRequest;
import com.axiom.order.dto.SkippedSignalResponse;
import com.axiom.order.service.SkippedSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class SkippedSignalController {

    private final SkippedSignalService skippedSignalService;

    /** strategy-service → 스킵 기록 저장 */
    @PostMapping("/skipped")
    public ResponseEntity<Void> record(@RequestBody SkippedSignalRequest request) {
        skippedSignalService.record(request);
        return ResponseEntity.ok().build();
    }

    /** 프론트엔드 → 최근 N일 스킵 목록 조회 (기본 7일) */
    @GetMapping("/skipped")
    public ResponseEntity<List<SkippedSignalResponse>> getSkipped(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(skippedSignalService.getRecent(days));
    }
}
