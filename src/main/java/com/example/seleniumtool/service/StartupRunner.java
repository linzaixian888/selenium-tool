package com.example.seleniumtool.service;

import com.example.seleniumtool.config.AutomationProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements ApplicationRunner {

    private final AutomationProperties properties;
    private final BrowserAutomationService browserAutomationService;
    private final AutomationAlertState automationAlertState;
    private final WebhookNotificationService webhookNotificationService;

    public StartupRunner(
        AutomationProperties properties,
        BrowserAutomationService browserAutomationService,
        AutomationAlertState automationAlertState,
        WebhookNotificationService webhookNotificationService
    ) {
        this.properties = properties;
        this.browserAutomationService = browserAutomationService;
        this.automationAlertState = automationAlertState;
        this.webhookNotificationService = webhookNotificationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isRunOnStartup()) {
            browserAutomationService.executeOnce("启动时执行");
        }
        webhookNotificationService.sendOrThrow("启动成功", buildStartupSummary());
    }

    private String buildStartupSummary() {
        StringBuilder content = new StringBuilder(properties.getStartupNotification().getMessage());
        for (String warning : automationAlertState.drainStartupWarnings()) {
            content.append("\n\n告警:\n").append(warning);
        }
        return content.toString();
    }
}
