package com.axiom.strategy.admin;

import com.axiom.strategy.client.ModeClient;
import com.axiom.strategy.service.MarketStateService;
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
    private final MarketStateService marketStateService;
    private final ModeClient modeClient;

    /** 현재 관리자 설정 상태 조회 */
    @GetMapping("/status")
    public ResponseEntity<AdminStatusDto> getStatus() {
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 중단 — targetMode 파라미터로 특정 모드만 중단 가능 */
    @PostMapping("/pause")
    public ResponseEntity<AdminStatusDto> pause(@RequestParam(required = false) String targetMode) {
        adminConfigStore.setPaused(true, targetMode);
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 재개 — targetMode 파라미터로 특정 모드만 재개 가능 */
    @PostMapping("/resume")
    public ResponseEntity<AdminStatusDto> resume(@RequestParam(required = false) String targetMode) {
        adminConfigStore.setPaused(false, targetMode);
        return ResponseEntity.ok(currentStatus());
    }

    /** 투자 설정 변경 (부분 업데이트 허용) — tradingMode 필드로 모드 전환 가능 */
    @PatchMapping("/config")
    public ResponseEntity<AdminStatusDto> updateConfig(@RequestBody AdminConfigDto dto) {
        // 모드 전환 요청 처리
        if (dto.tradingMode() != null) {
            adminConfigStore.setTradingMode(dto.tradingMode());
            modeClient.propagateTradingMode(dto.tradingMode());
        }

        // 설정 변경 요청 처리 (tradingMode만 있고 설정 필드가 없으면 스킵)
        boolean hasSettingFields = dto.investAmountKrw() != null || dto.maxPositions() != null
                || dto.trailingStopPct() != null || dto.timeCutDays() != null || dto.indexDropBlockPct() != null
                || dto.bollingerMaxPositions() != null;
        if (hasSettingFields) {
            String target = dto.targetMode(); // null이면 active 모드
            AdminConfigStore.ModeSettings current = adminConfigStore.getSettings(
                    target != null ? target : adminConfigStore.getTradingMode());

            int    newInvest    = dto.investAmountKrw()       != null ? dto.investAmountKrw()       : current.investAmountKrw();
            int    newMaxPos    = dto.maxPositions()           != null ? dto.maxPositions()           : current.maxPositions();
            double newTs        = dto.trailingStopPct()        != null ? dto.trailingStopPct()        : current.trailingStopPct();
            int    newTc        = dto.timeCutDays()            != null ? dto.timeCutDays()            : current.timeCutDays();
            double newIdx       = dto.indexDropBlockPct()      != null ? dto.indexDropBlockPct()      : current.indexDropBlockPct();
            int    newBollinger = dto.bollingerMaxPositions()  != null ? dto.bollingerMaxPositions()  : current.bollingerMaxPositions();
            adminConfigStore.setConfig(target, newInvest, newMaxPos, newTs, newTc, newIdx, newBollinger);
        }

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
        AdminConfigStore.ModeSettings paper = adminConfigStore.getPaperSettings();
        AdminConfigStore.ModeSettings real  = adminConfigStore.getRealSettings();
        return new AdminStatusDto(
                adminConfigStore.getTradingMode(),
                new AdminStatusDto.ModeSettingsDto(
                        paper.paused(), paper.investAmountKrw(), paper.maxPositions(),
                        paper.trailingStopPct(), paper.timeCutDays(), paper.indexDropBlockPct(),
                        paper.bollingerMaxPositions()),
                new AdminStatusDto.ModeSettingsDto(
                        real.paused(), real.investAmountKrw(), real.maxPositions(),
                        real.trailingStopPct(), real.timeCutDays(), real.indexDropBlockPct(),
                        real.bollingerMaxPositions()),
                marketStateService.isIndexDropBlockedToday(),
                marketStateService.isIndexDropCheckedToday()
        );
    }
}
