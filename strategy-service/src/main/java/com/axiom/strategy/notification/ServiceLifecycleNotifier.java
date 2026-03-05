package com.axiom.strategy.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceLifecycleNotifier implements ApplicationListener<ApplicationReadyEvent> {

    private final SlackNotifier slackNotifier;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        slackNotifier.sendServiceStarted();
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        slackNotifier.sendServiceStopped();
    }
}
