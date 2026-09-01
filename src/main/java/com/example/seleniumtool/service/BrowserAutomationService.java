package com.example.seleniumtool.service;

import com.example.seleniumtool.browser.WebDriverFactory;
import com.example.seleniumtool.config.AutomationProperties;
import com.example.seleniumtool.cookie.CookieCloudClient;
import com.example.seleniumtool.cookie.CookieCloudCookie;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.InvalidCookieDomainException;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;

@Service
public class BrowserAutomationService {

    public enum ExecutionRequestResult {
        ACCEPTED,
        BUSY,
        TARGET_NOT_FOUND
    }

    public enum BatchRetryRequestResult {
        ACCEPTED,
        BUSY,
        NOTHING_TO_RETRY
    }

    public record BatchRetrySubmission(
            BatchRetryRequestResult result,
            List<String> targetUrls
    ) { }

    private static final Logger log = LoggerFactory.getLogger(BrowserAutomationService.class);
    private static final String IDLE_URL = "https://www.baidu.com";
    private static final String TASK_RUNNING_MESSAGE = "任务仍在执行中，请稍候再试";

    private final AutomationProperties properties;
    private final WebDriverFactory webDriverFactory;
    private final CookieCloudClient cookieCloudClient;
    private final AutomationAlertState automationAlertState;
    private final WebhookNotificationService webhookNotificationService;
    private final TargetRunHistoryService targetRunHistoryService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private RemoteWebDriver sharedDriver;

    public BrowserAutomationService(
            AutomationProperties properties,
            WebDriverFactory webDriverFactory,
            CookieCloudClient cookieCloudClient,
            AutomationAlertState automationAlertState,
            WebhookNotificationService webhookNotificationService,
            TargetRunHistoryService targetRunHistoryService
    ) {
        this.properties = properties;
        this.webDriverFactory = webDriverFactory;
        this.cookieCloudClient = cookieCloudClient;
        this.automationAlertState = automationAlertState;
        this.webhookNotificationService = webhookNotificationService;
        this.targetRunHistoryService = targetRunHistoryService;
    }

    public boolean executeOnce() {
        return executeOnce("目标执行");
    }

    public boolean executeOnce(String notificationName) {
        if (!acquireExecution()) {
            return false;
        }
        List<AutomationProperties.Target> targets = List.copyOf(properties.getTargets());
        try {
            executeTargetTask(
                    targets,
                    notificationName,
                    () -> doExecuteTargets(targets, notificationName + "任务")
            );
            return true;
        } finally {
            running.set(false);
        }
    }

    public boolean tryExecuteAsync(Executor executor) {
        if (!acquireExecution()) {
            return false;
        }
        List<AutomationProperties.Target> targets = List.copyOf(properties.getTargets());
        try {
            executor.execute(() -> {
                try {
                    executeTargetTask(
                            targets,
                            "立即执行",
                            () -> doExecuteTargets(targets, "立即执行任务")
                    );
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    public ExecutionRequestResult tryExecuteTargetAsync(String targetUrl, Executor executor) {
        AutomationProperties.Target target = properties.getTargets().stream()
                .filter(item -> targetUrl.equals(item.getUrl()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return ExecutionRequestResult.TARGET_NOT_FOUND;
        }
        if (!acquireExecution()) {
            return ExecutionRequestResult.BUSY;
        }
        try {
            executor.execute(() -> {
                try {
                    executeTargetTask(
                            List.of(target),
                            "目标单独重试",
                            () -> doExecuteTarget(target)
                    );
                } finally {
                    running.set(false);
                }
            });
            return ExecutionRequestResult.ACCEPTED;
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    public BatchRetrySubmission tryExecuteFailedTargetsAsync(Executor executor) {
        if (findFailedOrUnrunTargets().isEmpty()) {
            return new BatchRetrySubmission(BatchRetryRequestResult.NOTHING_TO_RETRY, List.of());
        }
        if (!acquireExecution()) {
            return new BatchRetrySubmission(BatchRetryRequestResult.BUSY, List.of());
        }
        try {
            List<AutomationProperties.Target> retryTargets = findFailedOrUnrunTargets();
            if (retryTargets.isEmpty()) {
                running.set(false);
                return new BatchRetrySubmission(
                        BatchRetryRequestResult.NOTHING_TO_RETRY,
                        List.of()
                );
            }
            List<String> targetUrls = retryTargets.stream()
                    .map(AutomationProperties.Target::getUrl)
                    .toList();
            executor.execute(() -> {
                try {
                    executeTargetTask(
                            retryTargets,
                            "失败目标重试",
                            () -> doExecuteTargets(retryTargets, "失败目标重试任务")
                    );
                } finally {
                    running.set(false);
                }
            });
            return new BatchRetrySubmission(BatchRetryRequestResult.ACCEPTED, targetUrls);
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    private List<AutomationProperties.Target> findFailedOrUnrunTargets() {
        Map<String, TargetRunHistoryService.TargetRunRecord> latest =
                targetRunHistoryService.getLatestHistories();
        return properties.getTargets().stream()
                .filter(target -> {
                    TargetRunHistoryService.TargetRunRecord record = latest.get(target.getUrl());
                    return record == null || !record.success();
                })
                .toList();
    }

    private void executeTargetTask(
            List<AutomationProperties.Target> targets,
            String notificationName,
            Runnable task
    ) {
        Map<String, TargetRunHistoryService.TargetRunRecord> previous =
                targetRunHistoryService.getLatestHistories();
        RuntimeException taskFailure = null;
        try {
            task.run();
        } catch (RuntimeException ex) {
            taskFailure = ex;
            throw ex;
        } finally {
            sendTargetTaskNotification(targets, notificationName, previous, taskFailure);
            automationAlertState.drainTargetFailures();
        }
    }

    private void sendTargetTaskNotification(
            List<AutomationProperties.Target> targets,
            String notificationName,
            Map<String, TargetRunHistoryService.TargetRunRecord> previous,
            RuntimeException taskFailure
    ) {
        Map<String, TargetRunHistoryService.TargetRunRecord> latest =
                targetRunHistoryService.getLatestHistories();
        List<AutomationProperties.Target> failedTargets = targets.stream()
                .filter(target -> {
                    TargetRunHistoryService.TargetRunRecord record = latest.get(target.getUrl());
                    return record == null
                            || record.equals(previous.get(target.getUrl()))
                            || !record.success();
                })
                .toList();
        int successCount = targets.size() - failedTargets.size();
        String status = taskFailure == null && failedTargets.isEmpty()
                ? notificationName + "完成"
                : notificationName + "完成（存在失败）";
        StringBuilder content = new StringBuilder(notificationName).append("任务已结束")
                .append("\n目标数: ").append(targets.size())
                .append("\n成功: ").append(successCount)
                .append("\n失败: ").append(failedTargets.size());
        if (taskFailure != null) {
            content.append("\n异常: ").append(buildFailureMessage(taskFailure));
        }
        if (!failedTargets.isEmpty()) {
            content.append("\n\n失败目标:");
            for (AutomationProperties.Target target : failedTargets) {
                TargetRunHistoryService.TargetRunRecord record = latest.get(target.getUrl());
                boolean executed = record != null && !record.equals(previous.get(target.getUrl()));
                content.append("\n目标: ").append(target.getName())
                        .append("\nURL: ").append(target.getUrl())
                        .append("\n原因: ").append(executed ? record.message() : "本次任务未生成运行记录");
            }
        }
        webhookNotificationService.send(status, content.toString());
    }

    public boolean isRunning() {
        return running.get();
    }

    private boolean acquireExecution() {
        if (running.compareAndSet(false, true)) {
            return true;
        }
        log.info(TASK_RUNNING_MESSAGE);
        webhookNotificationService.send("任务仍在执行中", TASK_RUNNING_MESSAGE);
        return false;
    }

    private void doExecuteTargets(List<AutomationProperties.Target> targets, String taskName) {
        log.info("开始执行{}，共 {} 个目标", taskName, targets.size());

        // 在循环前一次性获取 CookieCloud 数据，避免循环中重复请求
        JsonNode cookieData = null;
        try {
            cookieData = cookieCloudClient.fetchAllCookies();
        } catch (IllegalStateException ex) {
            log.error("{}的 CookieCloud 获取失败且不允许回退缓存，中止任务", taskName, ex);
            targets.forEach(target -> targetRunHistoryService.record(
                    target.getUrl(),
                    false,
                    "CookieCloud 获取失败，任务已中止"
            ));
            return;
        }

        RemoteWebDriver driver = getOrCreateDriver();
        boolean interrupted = false;
        int i = 0;
        for (AutomationProperties.Target target : targets) {
            log.info("开始处理第{}个目标 {}", ++i, target.getName());
            if (!visitTarget(driver, target, cookieData)) {
                interrupted = true;
                break;
            }
        }
        if (!interrupted && isDriverAlive(driver)) {
            closeDriverQuietly(driver);
        }
        log.info("{}执行结束", taskName);
    }

    private void doExecuteTarget(AutomationProperties.Target target) {
        log.info("开始单独重试目标 [{}] {}", target.getName(), target.getUrl());
        JsonNode cookieData;
        try {
            cookieData = cookieCloudClient.fetchAllCookies();
        } catch (IllegalStateException ex) {
            log.error("单独重试目标 [{}] 时 CookieCloud 获取失败", target.getName(), ex);
            targetRunHistoryService.record(target.getUrl(), false, "CookieCloud 获取失败，任务已中止");
            return;
        }

        try {
            RemoteWebDriver driver = getOrCreateDriver();
            boolean completed = visitTarget(driver, target, cookieData);
            if (completed && isDriverAlive(driver)) {
                closeDriverQuietly(driver);
            }
        } catch (Exception ex) {
            log.error("单独重试目标 [{}] 失败 {}", target.getName(), target.getUrl(), ex);
            targetRunHistoryService.record(target.getUrl(), false, buildFailureMessage(ex));
        }
        log.info("目标 [{}] 单独重试结束", target.getName());
    }

    private boolean visitTarget(RemoteWebDriver driver, AutomationProperties.Target target, JsonNode cookieData) {
        log.info("开始打开目标 [{}] {}", target.getName(), target.getUrl());
        try {
            String warmupUrl = buildWarmupUrl(target);
            log.info("先访问预热地址 [{}] {}", target.getName(), warmupUrl);
            driver.get(warmupUrl);

            setCookie(driver, target, cookieData);
            setLocalStorage(driver, target);

            long startTime = System.currentTimeMillis();
            driver.navigate().to(target.getUrl());
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("目标 [{}] 页面导航耗时 {} ms", target.getName(), elapsed);
            if (!checkTargetStatus(driver, target)) {
                log.info("目标 [{}] 登录失败", target.getName());
                automationAlertState.addTargetFailure(
                        "目标: " + target.getName() + "\nURL: " + target.getUrl());
                targetRunHistoryService.record(target.getUrl(), false, "登录状态检查失败");
                return true;
            }
            sleep(Duration.ofSeconds(properties.getBrowser().getPageStaySeconds()));
            log.info("目标 [{}] 执行完成", target.getName());
            targetRunHistoryService.record(target.getUrl(), true, "执行成功");
        } catch (Exception ex) {
            if (isBrowserClosedException(ex)) {
                targetRunHistoryService.record(target.getUrl(), false, "浏览器已关闭");
                handleBrowserClosed(target, ex);
                return false;
            }
            log.error("目标 [{}] 执行失败 {}", target.getName(), target.getUrl(), ex);
            automationAlertState.addTargetFailure(
                    "目标: " + target.getName() + "\nURL: " + target.getUrl());
            targetRunHistoryService.record(target.getUrl(), false, buildFailureMessage(ex));
        }
        return true;
    }

    private String buildFailureMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return "执行失败：" + ex.getClass().getSimpleName();
        }
        String firstLine = message.lines().findFirst().orElse(message);
        if (firstLine.length() > 300) {
            firstLine = firstLine.substring(0, 300) + "…";
        }
        return "执行失败：" + firstLine;
    }

    private void setCookie(RemoteWebDriver driver, AutomationProperties.Target target, JsonNode cookieData) {
        // 清除当前域名下的旧 Cookie，避免与新注入的 Cookie 冲突
        driver.manage().deleteAllCookies();
        log.info("已清除目标 [{}] 预热页面上的所有旧 Cookie", target.getName());

        List<CookieCloudCookie> cookies;
        if (cookieData != null) {
            cookies = cookieCloudClient.filterCookiesForDomain(
                    cookieData,
                    target.getUrl(),
                    target.getCookieDomain()
            );
        } else {
            cookies = List.of();
        }

        if (cookies.isEmpty()) {
            log.warn("目标 [{}] 未解析到任何 CookieCloud Cookie，配置域名 [{}]", target.getName(), target.getCookieDomain());
        } else {
            log.info("目标 [{}] 共解析到 {} 个 CookieCloud Cookie", target.getName(), cookies.size());
        }

        int injectedCount = 0;
        for (CookieCloudCookie source : cookies) {
            try {
                String cookieName = source.getName();
                if(cookieName.startsWith("__Host-")){
                    driver.manage().addCookie(
                            new Cookie.Builder(cookieName, source.getValue())
                                    .path(source.getPath())
                                    .isSecure(source.isSecure())
                                    .isHttpOnly(source.isHttpOnly())
                                    .build()
                    );
                }else{
                    driver.manage().addCookie(
                            new Cookie.Builder(cookieName, source.getValue())
                                    .domain(source.getDomain())
                                    .path(source.getPath())
                                    .isSecure(source.isSecure())
                                    .isHttpOnly(source.isHttpOnly())
                                    .build()
                    );
                }

                injectedCount++;
            } catch (InvalidCookieDomainException ex) {
                log.warn(
                        "跳过 Cookie [{}]，因为域名 [{}] 与当前站点不匹配",
                        source.getName(),
                        source.getDomain()
                );
            }
        }
        log.info("目标 [{}] 成功注入 {} 个 Cookie", target.getName(), injectedCount);
    }
    private void setLocalStorage(RemoteWebDriver driver, AutomationProperties.Target target) {
        if(!CollectionUtils.isEmpty(target.getLocalStorage())) {
            for (AutomationProperties.Target.LocalStorage item : target.getLocalStorage()) {
                driver.executeScript("localStorage.setItem(arguments[0], arguments[1]);", item.getKey(), item.getValue());
            }
        }
    }

    private RemoteWebDriver getOrCreateDriver() {
        if (isDriverAlive(sharedDriver)) {
            return sharedDriver;
        }
        closeDriverQuietly(sharedDriver);
        sharedDriver = webDriverFactory.createChromeDriver();
        log.info("已创建新的浏览器会话");
        return sharedDriver;
    }


    private boolean isDriverAlive(RemoteWebDriver driver) {
        if (driver == null) {
            return false;
        }
        try {
            driver.getWindowHandles();
            return true;
        } catch (WebDriverException ex) {
            log.info("现有浏览器会话已失效，将自动创建新会话");
            return false;
        }
    }

    private void keepBrowserOpen(RemoteWebDriver driver) {
        try {
            driver.navigate().to(IDLE_URL);
            log.info("浏览器保持打开，当前停留在 {}", IDLE_URL);
        } catch (Exception ex) {
            if (isBrowserClosedException(ex)) {
                sharedDriver = null;
                log.info("浏览器在跳转到待机页面前已被关闭");
                return;
            }
            log.warn("浏览器跳转到待机页面失败 {}", IDLE_URL, ex);
        }
    }

    private String buildWarmupUrl(AutomationProperties.Target target) {
        URI uri = URI.create(target.getUrl());
        String warmupPath = target.getWarmupPath();
        if (!StringUtils.hasText(warmupPath)) {
            warmupPath = "/favicon.ico";
        }
        if (!warmupPath.startsWith("/")) {
            warmupPath = "/" + warmupPath;
        }
        return uri.getScheme() + "://" + uri.getHost() + warmupPath;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("浏览器自动化任务被中断", ex);
        }
    }
    private final String[] FAIL_TITLE = {"登录", "登録","登錄","异地", "2fa", "Login"};
    private final String[] SUCCESS_TITLE = {"首页", "首頁"};
    private final String[] SUCCESS_PAGE = {"首页", "首頁", "Torrents", "种子"};

    private boolean checkTargetStatus(RemoteWebDriver driver, AutomationProperties.Target target) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5),Duration.ofSeconds(1));
            return wait.until(d -> {
                String title = d.getTitle();
                if (StringUtils.hasText(title)) {
                    for (String failTitle : FAIL_TITLE) {
                        if (title.contains(failTitle)) {
                            return false;
                        }
                    }
                    for (String successTitle : SUCCESS_TITLE) {
                        if (title.contains(successTitle)) {
                            return true;
                        }
                    }
                    String pageSource = d.getPageSource();
                    if (StringUtils.hasText(pageSource)) {
                        for (String successPage : SUCCESS_PAGE) {
                            if (pageSource.contains(successPage)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            });
        } catch (TimeoutException e) {
            log.warn("目标 [{}] 等待页面加载超时，未匹配到预期内容", target.getName());
            return false;
        }
    }
    private boolean isBrowserClosedException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof NoSuchSessionException) {
                return true;
            }
            if (current instanceof WebDriverException && !(current instanceof InvalidCookieDomainException)) {
                String message = current.getMessage();
                if (StringUtils.hasText(message)) {
                    String normalized = message.toLowerCase();
                    if (normalized.contains("session deleted as the browser has closed the connection")
                            || normalized.contains("invalid session id")
                            || normalized.contains("not connected to devtools")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void handleBrowserClosed(AutomationProperties.Target target, Exception ex) {
        sharedDriver = null;
        webhookNotificationService.send(
                "浏览器已关闭",
                "执行过程中浏览器被手动关闭\n目标: " + target.getName() + "\nURL: " + target.getUrl()
        );
        log.warn("目标 [{}] 执行过程中浏览器被关闭 {}", target.getName(), target.getUrl(), ex);
    }

    @PreDestroy
    public synchronized void shutdown() {
        closeDriverQuietly(sharedDriver);
        sharedDriver = null;
    }

    private void closeDriverQuietly(RemoteWebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception ex) {
            log.debug("关闭浏览器会话时发生异常", ex);
        }
    }
}
