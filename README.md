# selenium-tool

基于 Java、Spring Boot、Selenium 的定时浏览器任务工具，支持从 CookieCloud 拉取 Cookie，并通过 webhook 发送启动结果和异常告警。

Docker 镜像支持 `linux/amd64` 和 `linux/arm64`，拉取同一标签时 Docker 会自动选择当前主机对应的架构。由于 Google Chrome 不提供 Linux/ARM64 版本，容器内统一使用与 ChromeDriver 兼容的 Chromium。

## 配置示例


### `config/custom.yml`（个性化配置，按需修改）

```yaml
automation:
  schedule:
    # Spring 六段式 cron，例如每天 10 点 0 分执行一次
    cron: "0 0 10 * * *"
  browser:
    # Selenium Manager 下载 chromedriver 及 Chrome 浏览器访问 URL 时使用的代理地址
    # 例如 http://127.0.0.1:7897；不配置时将禁用代理
    proxy:
  cookie-cloud:
    # cookieCloud 服务根地址，代码会自动拼接 /cookiecloud/get/{key}；未配置时 CookieCloud 功能自动禁用
    url: "https://mp域名"
    # CookieCloud 访问 key
    key: "CookieCloud 访问 key"
    # CookieCloud 访问密码
    password: "CookieCloud 访问密码"
  startup-notification:
    # webhook通知地址, 例如企业微信的webhook地址，不配置则不发送通知
    webhook-url:
```

### `docker-compose.yml`
```yaml
services:
  selenium-tool:
    image: linzaixian/selenium-tool:latest
    container_name: selenium-tool
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      SE_VNC_PASSWORD: selenium # vnc控制台密码
    volumes:
      - ./config:/app/config
    ports:
      - "7900:7900" # vnc控制台端口
      - "8080:8080" # web端配置端口
```
