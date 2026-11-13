# java-spider-reddit

`java-spider-reddit` 是一个独立的 Spring Boot 命令行项目。它根据飞书调研文档中的页面路径，抓取指定 subreddit 的帖子作者和评论作者，再读取公开个人主页中的用户 ID、简介和社交链接，最终写入本地 CSV。

## 环境要求

- macOS
- JDK 17 或更高版本
- 首次构建需要联网下载 Maven 与依赖

## 运行

```bash
chmod +x run.sh mvnw
./run.sh
```

默认抓取 `wallstreetbets`，列表页最多抓取 10 页，并访问这些列表页中发现的全部评论页。可通过环境变量调整列表页上限和请求间隔：

评论页链接取自有 `id` 的帖子 `article` 内的 `shreddit-post permalink`；只有 `comment-count` 大于 0 才访问。

```bash
REDDIT_COMMUNITY=java \
REDDIT_MAX_LISTING_PAGES=5 \
REDDIT_REQUEST_DELAY=3s \
./run.sh
```

## 本地文件

- `output/yyyy-MM-dd-HHmmss.csv`：每次运行的新增用户，社交链接在同一字段内按行分隔；简介和社交链接同时为空的用户不会写入 CSV。
- `data/visited-users.txt`：跨运行持久化的全局用户 ID 缓存；只有成功解析的主页才会写入。
- `logs/java-spider-reddit.log`：当前日志；历史日志按天压缩并保留 5 天。

每次 HTTP 请求都会记录脱敏后的 curl、请求耗时、响应状态码和响应正文的前 2000 个字符。Cookie 与 challenge 参数不会以明文写入日志；响应正文可能包含公开用户资料，请勿随意分享日志文件。

如需重新抓取全部用户，请先备份并手工删除 `data/visited-users.txt`。如果 Reddit 页面结构或 JavaScript challenge 发生变化，程序会记录具体失败 URL，并继续处理其他页面。

请遵守 Reddit 的服务条款、robots 规则和当地法律，控制请求频率，不要抓取或传播非公开信息。

## 测试与构建

```bash
./mvnw test
./mvnw package
```
