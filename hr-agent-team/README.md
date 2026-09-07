# hr-agent-team（Java / Spring Boot）

> 5 个数字员工协同招聘项目。**后端统一用 Spring Boot（Java）**，agent 也用 Java 写，后面直接接入，不返工。
> 上传必须遵守 `SKILL-上传规则.md`；新手先看 `入门说明书.md`。

## 这是什么工程

一个标准 **Maven + Spring Boot** 工程（Java 17）。每个数字员工 = 一个实现 `Agent` 接口的类（`run(Map payload) → Map`，吃进 JSON、吐出 JSON）。

## 目录结构与归属（谁只准碰哪一格）

```
hr-agent-team/
├── pom.xml                                   共享·只读（构建配置）
├── README.md / SKILL-上传规则.md / 入门说明书.md / 成员手册/   共享·只读
└── src/main/java/com/hragent/
    ├── HrAgentApplication.java                共享·启动入口
    ├── common/DeepSeekClient.java             共享·全项目唯一"调模型 + 调工具"类
    ├── agent/
    │   ├── Agent.java                          共享·接口（不要改）
    │   ├── analyst/AnalystAgent.java           成员 A 专属：招聘分析师
    │   ├── scout/ScoutAgent.java               成员 B 专属：简历猎手
    │   ├── interviewer/InterviewerAgent.java   成员 C 专属：面试官入口
    │   │   ├── controller/                    面试 API
    │   │   ├── session/                       会话状态机
    │   │   ├── prompt/                        行业题库与 PromptPack
    │   │   ├── media/                         媒体分片与本地风险信号
    │   │   ├── realtime/                      字幕、追问、WebSocket
    │   │   ├── scoring/                       评分卡计算
    │   │   ├── persistence/                   JDBC 快照与事件日志
    │   │   └── security/                      会话与复核鉴权
    │   ├── assessor/AssessorAgent.java         成员 D 专属：测评背调员
    │   └── concierge/ConciergeAgent.java       成员 E 专属：offer 与入职管家
    └── dispatcher/                             (整合者后续写) 按顺序串联 5 个 agent
```

## 归属速查

| 成员 | 数字员工 | 只准编辑这个文件 |
|---|---|---|
| A | 招聘分析师 | `agent/analyst/AnalystAgent.java` |
| B | 简历猎手 | `agent/scout/ScoutAgent.java` |
| C | 面试官 | `agent/interviewer/InterviewerAgent.java` |
| D | 测评背调员 | `agent/assessor/AssessorAgent.java` |
| E | offer 与入职管家 | `agent/concierge/ConciergeAgent.java` |

## 怎么跑

1. 用 **IDEA** 打开本目录，或执行 `mvn spring-boot:run`；
2. 可选配置 `DEEPSEEK_API_KEY`（没有 Key 时题纲/纪要走模板降级）；
3. 浏览器打开 `http://localhost:8080/interview.html`，创建会话后按页面流程授权、开始和提交；
4. H2 文件库默认在 `./data/hr-agent`，生产通过 `HR_DB_URL/HR_DB_USER/HR_DB_PASSWORD` 切换数据库；
5. 媒体目录由 `HR_MEDIA_DIR` 配置，默认 `target/interview-media`；生产设置 `HR_ENV=prod`、`HR_MEDIA_ENCRYPTION_KEY`（或 `HR_REQUIRE_MEDIA_ENCRYPTION=true`），保留期由 `HR_MEDIA_RETENTION_DAYS` 配置（默认 7 天）。

### API 顺序与鉴权

`POST /api/interviews` 创建会话会返回一次性 `access_token`。后续 REST 请求必须带 `X-Interview-Token`；浏览器先调用 `/{id}/live-ticket`，再使用一次性 `?ticket=` 连接 WebSocket。人工复核、结果和交接接口还必须配置 `HR_REVIEWER_TOKEN` 并携带 `X-Reviewer-Token`。本地临时调试可设置 `HR_REQUIRE_AUTH=false`，生产应保持开启。

主要接口顺序：`consent → prepare → start → responses/captions/video-events/media/chunks → complete → score(异步) → review → handoff`。媒体上传完成后调用 `POST /api/interviews/{id}/media/{mediaId}/complete`，可用 `?sha256=` 校验合并文件。

## 三条铁律

1. 调模型只用 `common/DeepSeekClient` 的 `callJson` / `call`，不自己另写；
2. 每个 agent 只做 `吃进 JSON → 吐出 JSON`，字段照 `SKILLS技能注册表.md`，不自创；
3. 上传代码只准进自己那个 `.java` 文件，禁止改共享文件 / 别人的类 / `pom.xml`；
4. **每个 agent 必须真调用至少一个工具**（把成果落成 .csv/.html 文件），不只生成文字——详见《工具调用规范》。

> 契约文档（DESP / all_skill / SKILLS技能注册表 等）在 `D:\编程练习册\Agent_Create\文档库\02_契约与技能\`，属只读参考区。入口先看 `文档库\00_文档总目录.md`。
