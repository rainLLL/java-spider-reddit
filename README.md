# java-spider-reddit

`java-spider-reddit` 是一个 Spring Boot 命令行爬虫，不提供 Web 服务。它按 `/new/` 页面返回的顺序处理指定 subreddit 的帖子及评论，读取公开用户主页中的用户 ID、简介和指定平台的社交链接，写入本地 CSV。需求依据为[“Reddit 个人信息路径”](https://lcndaoy6xmpy.feishu.cn/docx/IFPtd7EZ2oJ04fxRNUwcGL8nnYd)。

## 交付给 macOS 用户

在 `dist/` 中选择与 Mac 芯片对应的 ZIP：Apple Silicon 使用 `java-spider-reddit-1.0.0-macos-arm64.zip`，Intel 使用 `java-spider-reddit-1.0.0-macos-x64.zip`。解压后保留整个文件夹，其中包括 `run.sh`、`target/java-spider-reddit-1.0.0.jar`、`runtime/`、`config/application.properties` 和使用说明。运行包内置 Java 17，用户不需要安装 JDK 或 Maven。

在终端进入解压后的目录，编辑 `config/application.properties` 中冒号后的默认值，再运行：

```bash
chmod +x run.sh
./run.sh
```

默认抓取 `wallstreetbets`，每次最多访问 100 个未命中缓存的用户主页。两个面向用户的配置项是：

```properties
reddit.crawler.community=${REDDIT_COMMUNITY:wallstreetbets}
reddit.crawler.max-profile-requests=${REDDIT_MAX_PROFILE_REQUESTS:100}
```

例如要抓取 `java`，将第一行冒号后的 `wallstreetbets` 改为 `java`。`max-profile-requests` 必须大于 0，没有固定的 100 上限；改配置文件后无需重建 JAR。也可以保留文件不变，通过环境变量临时指定：

```bash
REDDIT_COMMUNITY=java \
REDDIT_MAX_PROFILE_REQUESTS=200 \
./run.sh
```

`run.sh` 使用包内 Java；如果运行目录没有 `runtime/`，才使用电脑上已安装的 Java 17 或更高版本。源码目录缺少 JAR 时，脚本会调用 Maven Wrapper 构建，此时需要本机 JDK 和首次构建所需的网络连接。

## 抓取流程

程序先访问 subreddit 首页，沿用同一 HTTP/Cookie 会话处理可能出现的 JavaScript challenge；帖子从 `/r/{community}/new/` 时间排序列表读取。它按页面中的 `article[id]` 顺序，逐条收集帖子作者，再读取该帖评论页及后续评论页的作者。只有 `shreddit-post` 的 `comment-count` 大于 0 才请求其评论页。

收集到配置数量的不同且未命中缓存的用户主页后，停止请求后续评论或列表页。列表页没有固定页数上限；列表没有下一页或检测到列表分页循环时也会停止。最后才开始访问这些用户主页，访问数量不超过 `max-profile-requests`。

## 本地文件

- `output/yyyy-MM-dd-HHmmss.csv`：正常结束时的结果，列为 `user_id`、`profile_description`、`social_links`。多个社交链接在同一字段内换行；简介和社交链接同时为空的用户不导出。零条结果时文件仍有表头。
- `data/visited-users.txt`：跨运行去重的用户 ID 缓存，不区分大小写。成功解析但无可导出内容的用户也会缓存。
- `logs/java-spider-reddit.yyyy-MM-dd-HHmmss-SSS.log`：每次运行一个日志文件；启动时删除超过 5 天的旧运行日志。

默认日志记录脱敏后的 curl、请求耗时和响应状态码。响应正文经脱敏并截取前 2000 个字符后，在 `DEBUG` 级别记录；默认 `INFO` 级别不会打印正文。Cookie、challenge 参数、评论 cursor 和 CSRF token 不会以明文写入 curl 日志。日志仍可能包含公开用户资料，请勿随意分享。

如需重新抓取全部用户，请先备份并手工删除 `data/visited-users.txt`。请求出现 4xx 时会重试 3 次，仍失败则终止；若此时已收集到有效主页数据，会尽量先导出。其他可恢复的列表后续页、评论页或主页请求错误会记录 URL 并跳过。首次页面初始化失败会终止运行。

CSV 是 UTF-8 文本。如 WPS 直接打开显示空白或乱码，可在 WPS 中使用“从文本导入”，选择 UTF-8 编码和逗号分隔，并确认打开的是最新时间戳的文件。

请遵守 Reddit 的服务条款及当地适用规则，控制请求频率，不要抓取或传播非公开信息。

## 源码构建与重新打包

源码开发需要 JDK 17 或更高版本。修改源码后，必须显式重新构建；仅执行 `run.sh` 时，如果旧 JAR 已存在，脚本不会自动重建：

```bash
./mvnw test
./mvnw clean package
```

`dist/` 是本地生成目录，不提交到 Git。重新交付时，需要将新 JAR、`run.sh`、`config/application.properties` 和对应架构的 Java 17 运行环境重新组装成 ZIP；不要把本机 `output/`、`logs/` 或 `data/visited-users.txt` 打包进去。现有 ZIP 使用 Eclipse Temurin Java 17.0.21，已做本地签名校验，但没有 Apple Developer ID 签名或公证；跨机器下载后可能出现 macOS 安全提示。x64 包通过 Rosetta 启动验证，尚未在 Intel 真机验收。
