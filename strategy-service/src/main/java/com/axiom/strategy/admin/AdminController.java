package com.axiom.strategy.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/strategy/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminConfigStore adminConfigStore;

    /** 현재 관리자 설정 상태 조회 */
    @GetMapping("/status")
    public ResponseEntity<AdminStatusDto> getStatus() {
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 중단 */
    @PostMapping("/pause")
    public ResponseEntity<AdminStatusDto> pause() {
        adminConfigStore.setPaused(true);
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 재개 */
    @PostMapping("/resume")
    public ResponseEntity<AdminStatusDto> resume() {
        adminConfigStore.setPaused(false);
        return ResponseEntity.ok(currentStatus());
    }

    /** 투자 설정 변경 (부분 업데이트 허용) */
    @PatchMapping("/config")
    public ResponseEntity<AdminStatusDto> updateConfig(@RequestBody AdminConfigDto dto) {
        int    newInvest   = dto.investAmountKrw() != null ? dto.investAmountKrw() : adminConfigStore.getInvestAmountKrw();
        int    newMaxPos   = dto.maxPositions()    != null ? dto.maxPositions()    : adminConfigStore.getMaxPositions();
        double newTsStop   = dto.trailingStopPct() != null ? dto.trailingStopPct() : adminConfigStore.getTrailingStopPct();
        int    newTimeCut  = dto.timeCutDays()     != null ? dto.timeCutDays()     : adminConfigStore.getTimeCutDays();
        adminConfigStore.setConfig(newInvest, newMaxPos, newTsStop, newTimeCut);
        return ResponseEntity.ok(currentStatus());
    }

    private AdminStatusDto currentStatus() {
        return new AdminStatusDto(
                adminConfigStore.isPaused(),
                adminConfigStore.getInvestAmountKrw(),
                adminConfigStore.getMaxPositions(),
                adminConfigStore.getTrailingStopPct(),
                adminConfigStore.getTimeCutDays()
        );
    }
}
