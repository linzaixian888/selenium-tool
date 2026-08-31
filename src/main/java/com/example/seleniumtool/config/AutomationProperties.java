package com.example.seleniumtool.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "automation")
@Data
public class AutomationProperties {

    /**
     * 定时执行配置。
     */
    @Valid
    private final Schedule schedule = new Schedule();

    /**
     * 浏览器启动与页面停留配置。
     */
    @Valid
    private final Browser browser = new Browser();

    /**
     * CookieCloud 连接配置。
     */
    @Valid
    private final CookieCloud cookieCloud = new CookieCloud();

    @Valid
    private final StartupNotification startupNotification = new StartupNotification();

    /**
     * 是否在应用启动后立即执行一次任务。
     */
    private boolean runOnStartup = true;

    /**
     * 需要按顺序访问的目标站点列表。
     */
    @Valid
    private List<Target> targets = new ArrayList<>();
    @Data
    public static class Schedule {
        /**
         * spring 六段式 cron，例如每天 8 点 0 分执行一次
         */
        @NotBlank
        private String cron = "0 0 8 * * *";

        @NotBlank
        private String zone = "Asia/Shanghai";
    }
    @Data
    public static class Browser {
        private boolean headless = true;
        private boolean noSandbox = true;
        private boolean disableDevShmUsage = true;
        @Min(1)
        private long pageStaySeconds = 10;
        private Duration pageLoadTimeout = Duration.ofMinutes(1);
        /**
         * 浏览器可执行文件路径；本地通常可不填，Docker 中建议显式指定。
         */
        private String binaryPath;
        private String driverPath;
        /**
         * Selenium Manager 下载 ChromeDriver 时使用的代理地址，例如 http://127.0.0.1:7897。
         * 不配置时将尝试自动检测系统代理；设为空字符串则禁用代理。
         */
        private String proxy;
        private List<String> arguments = new ArrayList<>();
        /**
         * 通过 Chrome 偏好设置（profile.managed_default_content_settings.images=2）
         * 在浏览器层面完全禁止图片加载，比 CDP 拦截更彻底，适合完全不需要图片的场景。
         * true：禁止所有图片（默认）；false：不干预图片加载。
         */
        private boolean disableImages = true;
        /**
         * 通过 CDP (Chrome DevTools Protocol) 阻止指定后缀资源加载的后缀列表。
         * 列表中每个条目为文件后缀（不含点号），例如 gif、png、jpg。
         * 列表为空时不启用资源拦截功能。
         * 示例：["gif", "png", "jpg", "jpeg", "webp", "svg", "bmp", "ico"]
         */
        private List<String> blockResourceSuffixes = new ArrayList<>();
    }
    @Data
    public static class CookieCloud {
        /**
         * CookieCloud 服务根地址，例如 https://example.com；代码会自动拼接 /cookiecloud/get/{key}。
         * 未配置时 CookieCloud 功能自动禁用。
         */
        private String url;
        /**
         * CookieCloud 的访问 key。
         */
        private String key;
        /**
         * CookieCloud 的访问密码。
         */
        private String password;
        private String cacheFile = "cookiecloud-cache.json";
        /**
         * 当 CookieCloud 远端获取失败时，是否允许复用之前的缓存文件。
         * true：允许回退到缓存继续执行；false：直接抛出异常中止任务。
         */
        private boolean allowCacheFallback = true;

        /**
         * 是否启用了 CookieCloud，依据 url 是否已配置有效值判断。
         */
        public boolean isEnabled() {
            return StringUtils.hasText(url);
        }

    }
    @Data
    public static class Target {
        /** 使用内置模板创建时对应的模板 id；空值表示普通可编辑目标。 */
        private String templateId;
        private String name;

        @NotBlank
        private String url;
        /**
         * 指定从 CookieCloud 过滤 Cookie 时使用的域名；为空时从 URL 自动提取。
         */
        private String cookieDomain;
        private String warmupPath = "/favicon.ico";
        private List<LocalStorage> localStorage = new ArrayList<>();

        public String getName() {
            if (name == null || name.isBlank()) {
                try {
                    name= java.net.URI.create(url).getHost();
                    return name;
                } catch (Exception e) {
                    return url;
                }
            }
            return name;
        }
        @Data
        public static class LocalStorage {
            private String key;
            private String value;
        }

    }
    @Data
    public static class StartupNotification {
        /**
         * webhook通知地址
         */
        private String webhookUrl = "";
        private String message = "selenium-tool启动成功";

        /**
         * 是否启用了启动通知，依据 webhookUrl 是否已配置有效值判断。
         */
        public boolean isEnabled() {
            return StringUtils.hasText(webhookUrl);
        }
    }
}
