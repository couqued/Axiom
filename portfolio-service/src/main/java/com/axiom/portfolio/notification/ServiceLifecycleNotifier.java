package com.axiom.portfolio.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
public class ServiceLifecycleNotifier implements ApplicationListener<ApplicationReadyEvent> {

    @Value("${slack.webhook-url}")
    private String webhookUrl;

    @Value("${slack.enabled:false}")
    private boolean enabled;

    @Value("${spring.application.name}")
    private String serviceName;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        send("🟢 *" + serviceName + "* 시작  (" + now() + ")");
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        send("🔴 *" + serviceName + "* 종료  (" + now() + ")");
    }

    private void send(String text) {
        if (!enabled || "PLACEHOLDER".equals(webhookUrl)) {
            log.info("[Slack] {}", text);
            return;
        }
        try {
            WebClient.builder().build()
                    .post().uri(webhookUrl)
                    .bodyValue(Map.of("text", text))
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("[Slack] 서비스 알림 전송 실패: {}", e.getMessage());
        }
    }

    private String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
