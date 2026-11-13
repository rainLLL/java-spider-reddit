# AGENTS.md

## 适用范围

本文件适用于整个仓库。项目名为 `java-spider-reddit`，是面向 macOS 用户交付的 Spring Boot 命令行爬虫，不是 Web 服务。需求依据为飞书文档“Reddit 个人信息路径”：从 subreddit 列表页和评论页发现公开用户主页，提取用户 ID、个人简介和零到多个社交链接，写入本地 CSV，并通过持久化用户 ID 缓存避免跨运行重复抓取。

## 技术基线

- Java 17，Spring Boot 3.5.x，Maven Wrapper。
- HTML 解析使用 jsoup；CSV 使用 Java 标准库写入 UTF-8 文件。
- 启动入口：`com.rain.reddit.RedditApplication`。
- 程序必须保持非 Web 模式；除非新增了明确的服务端需求，不要引入 Controller、数据库或消息队列。
- 用户入口是仓库根目录的 `run.sh`。修改构建产物名或启动参数时，必须同步更新该脚本和 `README.md`。
- 当前 `run.sh` 只在 jar 不存在时构建，源码变化后可能继续运行旧 jar。交付前必须显式执行 `./mvnw package`；修改脚本时应优先加入可靠的新旧检测，而不是依赖仓库中的 `target/`。

## 当前结构与职责

```text
src/main/java/com/rain/reddit/
├── RedditApplication.java        # 启动和顶层失败处理
├── client/RedditHttpClient.java  # HTTP、Cookie、限速、Reddit URL 白名单
├── config/CrawlerProperties.java # reddit.crawler 配置绑定和校验
├── domain/                       # Challenge、ListingPage、ProfileRecord 值对象
├── parser/RedditPageParser.java  # 纯 HTML/URI 解析，不做网络或文件 I/O
└── service/
    ├── CrawlService.java         # 抓取流程编排
    ├── CsvExportService.java     # CSV 输出
    └── VisitedUserCache.java     # 跨运行去重缓存
```

这是小型单体应用，当前按层分包是合理的。保持上述边界，不要为了形式拆成多模块。只有当某个类持续承担多个独立职责或出现第二种数据源/输出格式时，再抽取接口或按功能重组。

## 核心流程与不变量

1. 请求 subreddit 首页，沿用同一 `HttpClient`/Cookie 会话。
2. 若出现文档描述的 JavaScript challenge，解析隐藏字段和 seed，提交 challenge 查询参数。
3. 解析列表作者、评论页 URL 和下一页 URL；仅从带 `id` 的 `article` 内读取 `shreddit-post` 的 `permalink`，且 `comment-count` 必须大于 0；列表分页受 `max-listing-pages` 限制并检测循环。
4. 读取所处理列表页中发现的全部去重 comments URL，补充评论作者主页；不得增加评论页数量上限。
5. 用户 ID 比较不区分大小写；命中持久化缓存的用户不得重复请求主页。
6. 主页解析允许简介为空、社交链接为零个或多个；社交链接保持发现顺序并去重。简介和社交链接同时为空的用户写入访问缓存，但不得导出到 CSV。
7. 每次运行生成 `output/yyyy-MM-dd-HHmmss.csv`，列为 `user_id`、`profile_description`、`social_links`。
8. 仅将成功解析且已可靠写入本次结果的用户视为已完成。当前实现先写缓存、后导出 CSV，导出失败可能造成下次永久跳过；修改该流程时应优先改成“CSV 成功落盘后再提交缓存”，或采用等价的可恢复事务方案。
9. 单个列表后续页、评论页或主页失败时记录 URL 并继续；首页初始化失败可以终止整次运行。
10. 所有请求必须保留 HTTPS 和 Reddit host 白名单。不要让页面中的任意链接变成可请求目标。

## 配置、文件与日志

- 配置前缀统一为 `reddit.crawler`，默认值放在 `src/main/resources/application.yml`。
- 面向用户的可调项应提供 `REDDIT_*` 环境变量，并在 `README.md` 给出示例。
- 运行时文件只写入 `output/`、`data/` 和 `logs/`；不要把它们当作源码或测试夹具。
- 日志必须同时输出到控制台和 `logs/java-spider-reddit.log`，按天滚动并只保留 5 天。
- 每个 HTTP 请求必须记录可识别的请求序号、脱敏 curl、网络请求耗时、响应状态码和响应正文的前 2000 个字符；失败请求记录失败前耗时。
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
- 运行真实抓取属于手工验收，不是默认测试。执行前确认合规性、低请求频率和明确的页数上限。

## 修改检查清单

- 需求字段、页面路径和选择器是否仍与需求文档一致。
- 是否保持同一 Cookie 会话、请求延迟、列表页上限、全部 comments URL 访问和 URL 白名单。
- 失败是否可恢复，缓存与 CSV 是否不会出现“已缓存但未导出”的不一致。
- 新配置是否同步到 `application.yml`、环境变量和 `README.md`。
- 是否补齐与改动层次对应的测试，并执行 `./mvnw test`。
- 交付前是否执行 `./mvnw package`，确保 `run.sh` 不会启动旧 jar。
- 是否避免改动 `target/` 以及运行时生成目录。
