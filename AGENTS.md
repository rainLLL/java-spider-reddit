# AGENTS.md

## 适用范围

本文件适用于整个仓库。项目名为 `java-spider-reddit`，是面向 macOS 用户交付的 Spring Boot 命令行爬虫，不是 Web 服务。需求依据为[飞书文档“Reddit 个人信息路径”](https://lcndaoy6xmpy.feishu.cn/docx/IFPtd7EZ2oJ04fxRNUwcGL8nnYd)：从 subreddit 时间排序列表页和评论页发现公开用户主页，提取用户 ID、个人简介和零到多个指定平台的社交链接，写入本地 CSV，并通过持久化用户 ID 缓存避免跨运行重复抓取。

## 技术基线

- Java 17，Spring Boot 3.5.x，Maven Wrapper。
- HTML 解析使用 jsoup；CSV 使用 Java 标准库写入 UTF-8 文件。
- 启动入口：`com.rain.reddit.RedditApplication`。
- 程序必须保持非 Web 模式；除非新增了明确的服务端需求，不要引入 Controller、数据库或消息队列。
- 用户入口是仓库根目录的 `run.sh`。交付包按 macOS `arm64`、`x64` 分别提供内置 Java 17；修改构建产物名、运行环境布局或启动参数时，必须同步更新脚本、打包内容和 `README.md`。
- 当前 `run.sh` 只在 JAR 不存在时构建，源码变化后可能继续运行旧 JAR。交付前必须显式执行 `./mvnw package`，并将新 JAR 放入交付包。

## 当前结构与职责

```text
src/main/java/com/rain/reddit/
├── RedditApplication.java        # 启动和顶层失败处理
├── client/RedditHttpClient.java  # HTTP、Cookie、限速、Reddit URL 白名单
├── config/CrawlerProperties.java # reddit.crawler 配置绑定和校验
├── domain/                       # Challenge、ListingPost、ListingPage、CommentContinuation、ProfileRecord 等值对象
├── parser/RedditPageParser.java  # 纯 HTML/URI 解析，不做网络或文件 I/O
└── service/
    ├── CrawlService.java         # 抓取流程编排
    ├── CsvExportService.java     # CSV 输出
    └── VisitedUserCache.java     # 跨运行去重缓存
```

这是小型单体应用，当前按层分包是合理的。保持上述边界，不要为了形式拆成多模块。只有当某个类持续承担多个独立职责或出现第二种数据源/输出格式时，再抽取接口或按功能重组。

## 核心流程与不变量

1. 请求 subreddit 首页，沿用同一 `HttpClient`/Cookie 会话。若出现文档描述的 JavaScript challenge，解析并提交 challenge 查询参数。
2. 从 `/r/{community}/new/` 时间排序列表读取帖子。按页面中的 `article[id]` 顺序处理：先缓存该帖作者，再处理该帖评论作者，随后才进入下一帖。仅当 `shreddit-post` 的 `comment-count` 大于 0 时请求其 `permalink` 评论页。
3. 评论首页解析作者及后续评论请求；后续评论使用同一会话带 `cursor` 和 Cookie 中的 CSRF token 发起 POST。列表页和评论后续页都检测分页循环。没有固定的列表页或评论页数量上限；收集到 `max-profile-requests` 个不同且未访问的用户主页时停止继续发现。某帖没有后续评论时处理下一帖；列表没有下一页或出现列表分页循环时停止遍历列表。
4. 用户 ID 比较不区分大小写；命中持久化缓存的用户不得重复请求主页。`max-profile-requests` 默认 100，但由用户配置且只要求大于 0；不要在代码中写死 100 上限。
5. 主页解析允许简介为空、社交链接为零个或多个；仅保留指定平台的社交链接，并按发现顺序去重。简介和社交链接同时为空的用户写入访问缓存，但不得导出到 CSV。
6. 每次正常完成生成 `output/yyyy-MM-dd-HHmmss.csv`，列为 `user_id`、`profile_description`、`social_links`；零条结果也应保留表头。先成功导出有内容的用户，再提交他们的缓存，避免出现“已缓存但未导出”。
7. HTTP 4xx 最多重试 3 次，仍失败则终止；若错误发生在访问主页阶段，先尽力导出已收集的有效资料。其他可恢复的后续列表页、评论页或主页请求错误记录 URL 并跳过；首页或时间排序列表首页初始化失败会终止。
8. 所有请求必须保留 HTTPS 和 Reddit host 白名单。不要让页面中的任意链接变成可请求目标。

## 配置、文件与日志

- 配置前缀统一为 `reddit.crawler`，默认值放在 `src/main/resources/application.yml`。交付包同时包含可编辑的 `config/application.properties`，暴露 `community` 和 `max-profile-requests`，修改它无需重建 JAR。
- 面向用户的可调项应提供 `REDDIT_*` 环境变量，并在 `README.md` 给出示例。不要重新引入 `max-listing-pages`。
- 运行时文件只写入 `output/`、`data/` 和 `logs/`；不要把它们当作源码或测试夹具。
- 日志同时输出到控制台和每次运行独立的 `logs/java-spider-reddit.yyyy-MM-dd-HHmmss-SSS.log`；启动时清理超过 5 天的旧日志。
- 每个 HTTP 请求在 INFO 级别记录请求序号、脱敏 curl、网络请求耗时和响应状态码；失败请求记录失败前耗时。响应正文经脱敏并截取前 2000 个字符后仅在 DEBUG 级别记录。
- 不得记录 Cookie、challenge token 或其他敏感请求头；curl URL 和响应正文中的 challenge 字段必须先脱敏。
- `target/` 是 Maven 生成目录，不要手工编辑或依赖其中的旧 class/jar 判断源码行为。

## 编码约定

- 使用构造器注入，避免字段注入。
- 领域数据优先使用不可变 `record` 和不可变集合。
- Parser 保持确定性和无副作用：输入 HTML/URI，输出领域对象；页面选择器变化集中在 parser 中处理。
- HTTP 状态、超时、重试/退避、Cookie 和限速逻辑集中在 client 中；不要散落到 parser 或 exporter。
- 文件系统职责集中在 cache/export service 中。路径一律从 `CrawlerProperties` 获取并规范化。
- 避免捕获宽泛异常后静默忽略；可恢复错误至少记录失败 URL 和原因，不可恢复错误向顶层传播。
- 不要把真实 Cookie、用户资料样本、访问令牌或大段生产 HTML 提交到仓库。

## 测试要求

- 常规验证：`./mvnw test`。
- 交付构建：`./mvnw package`。
- 修改 CSS selector、URL 归一化或 challenge 解析时，必须在 `src/test/java/com/rain/reddit/parser/RedditPageParserTest.java` 增加最小 HTML fixture，至少覆盖正常、缺失可选字段和结构变化/非法输入。
- 修改缓存时，增加临时目录测试，覆盖大小写去重、跨实例恢复和写入失败。
- 修改 CSV 输出时，写入临时文件后按 UTF-8 读取，断言表头、空字段、引号转义、多链接换行和文件名格式。
- 修改抓取编排或 HTTP 行为时，使用本地 mock HTTP server 或可注入的 fake；单元测试不得请求真实 Reddit。
- 运行真实抓取属于手工验收，不是默认测试。执行前确认合规性和低请求频率，并用 `max-profile-requests` 控制访问用户主页的数量。

## 修改检查清单

- 需求字段、页面路径和选择器是否仍与需求文档一致。
- 是否保持同一 Cookie 会话、请求延迟、按帖子顺序读取评论、达到配置用户数后停止，以及 URL 白名单。
- 失败是否可恢复，缓存与 CSV 是否不会出现“已缓存但未导出”的不一致。
- 面向用户的新配置是否同步到 `application.yml`、`config/application.properties`、环境变量和 `README.md`。
- 是否补齐与改动层次对应的测试，并执行 `./mvnw test`。
- 交付前是否执行 `./mvnw package` 并更新两种架构的运行包，确保 `run.sh` 不会启动旧 JAR。
- 是否避免改动 `target/` 以及运行时生成目录。
