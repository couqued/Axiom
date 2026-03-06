package com.axiom.strategy.admin;

import com.axiom.strategy.service.TimeCutService;
import com.axiom.strategy.service.TrailingStopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/strategy/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminConfigStore adminConfigStore;
    private final TrailingStopService trailingStopService;
    private final TimeCutService timeCutService;

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

    /** 트레일링 스탑 현황 조회 — ticker별 고점/기준가 */
    @GetMapping("/trailing-stop-status")
    public ResponseEntity<Map<String, TrailingStopStatusDto>> getTrailingStopStatus() {
        return ResponseEntity.ok(trailingStopService.getStatus());
    }

    /** 타임 컷 현황 조회 — ticker별 매수일/경과/남은 거래일 */
    @GetMapping("/time-cut-status")
    public ResponseEntity<Map<String, TimeCutStatusDto>> getTimeCutStatus() {
        return ResponseEntity.ok(timeCutService.getStatus());
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
