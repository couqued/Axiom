package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.StockPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisMarketApiService {

    private final WebClient kisWebClient;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;

    public StockPriceDto getCurrentPrice(String ticker) {
        if (kisApiConfig.isMock()) {
            return getMockPrice(ticker);
        }
        return getKisPrice(ticker);
    }

    // ── Mock ────────────────────────────────────────────────────────────────

    private StockPriceDto getMockPrice(String ticker) {
        Map<String, Object[]> mockData = Map.of(
            "005930", new Object[]{"삼성전자",   75000},
            "000660", new Object[]{"SK하이닉스", 185000},
            "035420", new Object[]{"NAVER",       220000},
            "051910", new Object[]{"LG화학",      320000},
            "006400", new Object[]{"삼성SDI",     280000}
        );

        Object[] data     = mockData.getOrDefault(ticker, new Object[]{"알 수 없는 종목", 50000});
        String stockName  = (String) data[0];
        int basePrice     = (int) data[1];
        Random rand       = new Random();
        int change        = (rand.nextInt(201) - 100) * 100;
        BigDecimal currentPrice  = BigDecimal.valueOf(basePrice + change);
        BigDecimal changeAmount  = BigDecimal.valueOf(change);
        BigDecimal changeRate    = changeAmount.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(basePrice), 2, java.math.RoundingMode.HALF_UP);

        log.info("[MOCK] 현재가 조회 - ticker: {}, price: {}", ticker, currentPrice);

        return StockPriceDto.builder()
                .ticker(ticker)
                .stockName(stockName)
                .currentPrice(currentPrice)
                .changeAmount(changeAmount)
                .changeRate(changeRate)
                .highPrice(currentPrice.add(BigDecimal.valueOf(500)))
                .lowPrice(currentPrice.subtract(BigDecimal.valueOf(500)))
                .openPrice(BigDecimal.valueOf(basePrice))
                .volume(rand.nextLong(5000000) + 100000)
                .fetchedAt(LocalDateTime.now())
                .mock(true)
                .marketWarnCode("00")
                .build();
    }

    // ── Paper / Real ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private StockPriceDto getKisPrice(String ticker) {
        KisApiConfig.ModeConfig active = kisApiConfig.getActive();
        String token = kisTokenService.getAccessToken();

        log.info("[KIS-{}] 현재가 조회 - ticker: {}", kisApiConfig.getMode().toUpperCase(), ticker);

        Map<String, Object> response = kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("fid_cond_mrkt_div_code", "J")
                        .queryParam("fid_input_iscd", ticker)
                        .build())
                .header("authorization", "Bearer " + token)
                .header("appkey",    active.getAppKey())
                .header("appsecret", active.getAppSecret())
                .header("tr_id",     "FHKST01010100")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Map<String, String> output = (Map<String, String>) response.get("output");

        BigDecimal currentPrice = new BigDecimal(output.get("stck_prpr"));
        BigDecimal changeAmount = new BigDecimal(output.get("prdy_vrss"));
        BigDecimal changeRate   = new BigDecimal(output.get("prdy_ctrt"));

        // 종목명: bstp_kor_isnm (업종한글종목명) 사용, 없으면 ticker 반환
        String stockName = output.getOrDefault("bstp_kor_isnm", ticker);

        // 시장경보코드: "00"=정상, "01"=투자주의, "02"=투자경고, "03"=투자위험
        String marketWarnCode = output.getOrDefault("mrkt_warn_cls_code", "00");

        return StockPriceDto.builder()
                .ticker(ticker)
                .stockName(stockName)
                .currentPrice(currentPrice)
                .changeAmount(changeAmount)
                .changeRate(changeRate)
                .highPrice(new BigDecimal(output.get("stck_hgpr")))
                .lowPrice(new BigDecimal(output.get("stck_lwpr")))
                .openPrice(new BigDecimal(output.get("stck_oprc")))
                .volume(Long.parseLong(output.get("acml_vol")))
                .fetchedAt(LocalDateTime.now())
                .mock(false)
                .marketWarnCode(marketWarnCode)
                .build();
    }
}
