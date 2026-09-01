package com.example.seleniumtool.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    private final BrowserAutomationService browserAutomationService;

    public AutomationScheduler(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    /**
     * 按 cron 定时触发自动化任务。
     */
    @Scheduled(cron = "${automation.schedule.cron}", zone = "${automation.schedule.zone}")
    public void runDailyJob() {
        log.info("Triggered scheduled automation task");
        try {
            if (!browserAutomationService.executeOnce("定时执行")) {
                log.info("Scheduled automation task skipped because another task is running");
            }
        } catch (Exception ex) {
            log.error("Scheduled automation task failed", ex);
        }
    }
}
