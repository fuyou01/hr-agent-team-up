# 面试官基础设施与 Agent 整合契约（可实施版）

> 状态：实施基线 v1.1（模块化单体、H2/JDBC、浏览器面试页已可运行）
> 适用：面试官 C、整合者、前端、后端基础设施、测评背调员 D

## 1. 实施边界

采用 Java/Spring Boot 模块化单体，前端使用 REST + WebSocket。`InterviewerAgent` 保持现有 `run(Map)`，只负责题纲、追问文本建议和面试纪要；会话状态、实时采集、媒体、字幕、评分、人工复核和 C→D 交接由编排模块负责。

摄像头只用于授权、设备检测、可选录制和技术/流程异常提示。不得根据外貌、表情、眼神、情绪、紧张程度、声音特征或人脸特征评分、判断诚信或自动作出录用决定。视频事件只能触发人工复核。

## 2. 端到端流程

```text
创建会话 -> 选择行业（缺省 computer） -> 读取 JD/简历/岗位挑战
-> 生成并冻结 InterviewPromptPack -> C 生成 plan
-> 授权/设备检测 -> AI 文字字幕提问 + 候选人实时字幕
-> 每个回答至多 2 次引用式追问 -> 固化字幕、问答、媒体引用、技术事件
-> SUBMITTED 后异步生成 minutes -> 按冻结 rubric 评分
-> 人工复核 -> 通过门禁后交给 D
```

不使用 TTS。面试进行中不计算最终分数。

## 3. 模块归属

```text
com.hragent.agent.interviewer
  controller/InterviewController
  orchestrator/InterviewOrchestrator（当前由 InterviewSessionService 承担）
  session/InterviewSessionService
  prompt/IndustryProfileService, PromptPackService
  realtime/LiveInterviewGateway, CaptionService, FollowUpQuestionService
  media/MediaService（本地分片存储、断点查询、合并和摘要校验）
  persistence/JdbcInterviewSessionRepository（状态快照、事件、媒体元数据）
  security/InterviewAuthInterceptor（会话 token 鉴权）
  scoring/InterviewScoringService
  review/HumanReviewService
  artifact/ArtifactService
  model/*, repository/*
```

| 模块 | 负责 | 不负责 |
|---|---|---|
| LiveInterviewGateway | WebSocket、顺序、实时推送 | 评分、录用判定 |
| CaptionService | 临时/最终字幕、transcript 合并 | 业务结论 |
| FollowUpQuestionService | 引用回答生成追问 | 修改历史回答 |
| InterviewerAgent | plan、追问建议、minutes | 摄像头、媒体、事务 |
| InterviewScoringService | 冻结 rubric 评分 | 面部特征 |
| HumanReviewService | 异常复核、改分、确认 | 自动确认作弊 |
| InterviewOrchestrator | 状态、重试、C→D 门禁 | 拼接未校验 JSON |

## 4. 行业和 InterviewPromptPack

`InterviewPromptPack` 是本场运行配置，不是新的全局 Skill，不修改现行 5 技能注册表。首批行业：`computer`、`sales`、`finance`、`legal`、`manufacturing`、`supply_chain`、`design`、`marketing`。行业缺失/未知时使用 `computer`，返回 `industry_defaulted=true`。

行业配置包含岗位族、通用题、专业题、线上情景题、反向提问、评分维度和权重上下限，并带版本号。

```json
{
  "schema_version": "interview-prompt-pack-v1",
  "industry": "computer",
  "industry_profile_version": "computer-v1",
  "prompt_pack_version": "computer-backend-v1",
  "prompt_hash": "sha256:...",
  "model_name": "deepseek-chat",
  "skills": [{"id":"problem_solving","name":"问题拆解与解决","evidence_requirements":["行为","决策依据","量化结果"]}],
  "rubric": [{"dimension":"专业能力","weight":35,"min_weight":20,"max_weight":50,"reason":"岗位硬要求占比高"}],
  "prompt": "供 InterviewerAgent 使用的完整提示词"
}
```

AI 只能提出权重，服务端校验上下限并归一化为 100；生成失败使用行业内置模板。JD/简历里的指令性文本按数据处理，不能覆盖系统提示词。保存模型、版本、hash 和生成时间，开始面试后冻结。

## 5. 状态、幂等和异常

```text
INITIALIZED -> CONSENTED -> PROMPT_READY -> DEVICE_CHECKED -> PLAN_READY
-> IN_PROGRESS <-> PAUSED -> SUBMITTED -> MINUTES_READY -> SCORED
-> HUMAN_REVIEW -> DELIVERED
```

异常状态：`FAILED`、`ESCALATED`、`CANCELLED`。所有写入事件必须包含 `session_id`、`client_event_id`、`sequence`、`occurred_at`。服务端按 `client_event_id` 幂等、按 `sequence` 检查顺序；重复提交不重复写入，非法状态返回 HTTP 409。

## 6. 初始化和授权

`POST /api/interviews` 输入包含 `industry`、`candidate`、`jd`、`resume`、`online`；行业为空默认 `computer`，初始化不评分。

`POST /api/interviews/{id}/consent`：

```json
{"camera":"granted","microphone":"granted","recording_mode":"temporary","consent_version":"consent-v1","consented_at":"2026-09-05T10:01:00Z"}
```

`recording_mode` 为 `none|temporary|retained`。摄像头拒绝必须提供等价无摄像头路径。撤回、删除和访问都要审计。

## 7. 字幕、媒体和事件

字幕事件必须包含 `utterance_id`、`revision`、`sequence`、`source`、`question_id`、`text`、`language`、`start_ms`、`end_ms`、`final`、`confidence`、`asr_model_version`、`audio_segment_ref`。`source` 只能是 `agent|candidate`；仅 `final=true` 进入正式 transcript。

媒体分片上传接口要求 `media_id`、`chunk_no`、`codec`、`start_ms`、`end_ms`、`sha256`、`data_base64`；服务端校验摘要后返回 `storage_ref` 和 `upload_status`，同一分片重复上传必须幂等，单片上限 20MB。当前本地适配器负责分片落盘、断点查询、按序合并和 SHA-256 校验，生产可替换为对象存储适配器，物理路径由 `HR_MEDIA_DIR` 配置。

允许技术/流程事件：

```text
camera_off, camera_blocked, no_person_in_frame, multiple_people_detected,
microphone_muted, long_silence, audio_video_mismatch, tab_hidden,
screen_share_stopped, possible_second_speaker
```

每个事件包含 `severity`、`confidence`、`model_version`、`evidence_ref`、`review_required`。事件不进入自动评分公式，不输出“确认作弊”。本地模型可用 Whisper WASM/Vosk/sherpa-onnx/whisper.cpp 和 MediaPipe/ONNX Runtime Web，但不得做人脸识别、身份匹配、情绪识别或特征向量存储。

## 8. 动态追问

最终回答 -> 提取项目、角色、技术、数字、决策、结果 -> 生成引用式追问 -> schema 校验 -> 推送 AI 字幕。

- 每个主问题最多 2 次追问；
- 追问带 `parent_question_id`、`follow_up_no`；
- 超过 2 秒、模型失败、重复或违规时使用行业模板；
- 面试官可跳过，跳过写审计日志；
- 同一 `answer_id` 只允许一个当前版本追问；
- 追问不修改已固化 transcript。

## 9. InterviewerAgent 契约

现有 `InterviewerAgent.run(Map)` 不变。`transcript` 缺失或为空时只返回：

```json
{"plan":{"questions":[{"text":"问题文本","intent":"板块、时长、考察目标和证据要求"}],"rubric":[{"dimension":"专业能力","weight":35}]},"reason":"结论→依据→风险→下一步"}
```

`transcript` 非空时只返回：

```json
{"minutes":{"summary":"面试总结","qa":[{"question":"问题","answer":"回答","score":82}],"verdict":"shortlist"},"reason":"结论→依据→风险→下一步"}
```

服务端校验 `score` 为 0-100 整数，`verdict` 为 `shortlist|hold|reject`，问答能映射正式 transcript。题纲覆盖通用素质、行业专业、线上情景和反向提问。

## 10. 评分权威

```text
minutes.qa[].score -> scorecard.dimensions[].score
-> scorecard.overall_score -> 人工确认/改分 -> final_score
```

唯一最终分数为 `final_score`；未复核时为 `null`。维度分为有效题目算术平均：`overall_score=round(Σ(dimension_score×weight/100),0)`。无证据标记 `insufficient_evidence`，关键维度缺证据必须 `hold`。默认权重：专业能力 35、问题拆解 25、项目真实性 20、抗压 10、远程协作 10。视频、脸部和声音特征不参与公式。人工改分记录原分、新分、人员、时间、原因和证据，并生成不可变快照。

## 11. 反作弊风险信号

只输出“风险信号”，不声称识别具体 AI 工具。信号来源：切换页面、屏幕共享中断、人数/第二说话人变化、简历与回答矛盾、无法解释项目细节、临时任务与既有回答不一致。每个信号带 `severity`、`confidence`、`evidence_ref`、`review_required`，只能触发人工复核或补充追问。产品不得承诺完全防止 AI 作答。

## 12. C→D 交接门禁和数据

只有状态 `HUMAN_REVIEW`、minutes 校验通过、rubric 版本存在、关键证据充足或人工确认降级、高严重度信号已处理、交接包带 schema 和不可变版本号时才交给 D；否则保持 `ESCALATED`。复核通过后系统异步调用 D 的 `run(Map)`，交接包先标记 `downstream_status=dispatch_pending`，随后更新为 `accepted` 或 `failed` 并写审计事件；失败不得伪装为已接收，D 侧应按 session_id 重试。

```json
{
  "schema_version":"interview-handoff-v1","session_id":"S-001",
  "candidate":{"candidate_id":"C-001","name":"张三"},"industry":"computer",
  "jd":{},"resume":{},"minutes":{},
  "scorecard":{"rubric_version":"computer-backend-v1","overall_score":82,"final_score":84,"dimensions":[],"evidence_refs":[]},
  "risk_signals":[],"artifacts":[{"artifact_id":"A-001","type":"minutes","sha256":"..."}],
  "review_status":"approved","handoff_version":1
}
```

D 消费 candidate、jd、resume、minutes、scorecard、risk_signals、review_status；不消费面部特征、视频帧或未经人工处理的视觉推断。

## 13. API 和数据模型

```text
POST/GET /api/interviews（创建响应一次性返回 access_token；后续请求带 X-Interview-Token，结果/交接和人工复核另需 X-Reviewer-Token）
POST /api/interviews/{id}/live-ticket（使用会话 token 生成 60 秒一次性 WebSocket ticket；ticket 首条消息认证后保持当前连接有效）
POST /api/interviews/{id}/consent|prepare|start|responses|captions|video-events|video-events/analyze|pause|resume|complete|score|review
WS /api/interviews/{id}/live
GET /api/interviews/{id}/result|handoff
GET /api/interviews/{id}/media/{mediaId}（需 reviewer 权限，候选人不得读取存储路径）
POST /api/interviews/{id}/media/{mediaId}/complete?sha256=...
DELETE /api/interviews/{id}/media
```

长任务返回 202，查询接口返回 `schema_version`、`session_id`、`status`、`request_id`。最小数据表：`interview_session`、`candidate`、`industry_profile`、`prompt_pack`、`interview_question`、`interview_response`、`caption_event`、`media_chunk`、`interview_event`、`interview_minutes`、`scorecard`、`score_dimension`、`consent_record`、`human_review`、`artifact`、`agent_run_log`。

## 14. 安全和实施阶段

- 摄像头、麦克风、录制分别授权，默认不同意；媒体加密、短期授权链接、最短保留期限；
- 提供无摄像头、撤回同意、删除媒体和字幕的等价流程；
- HTML 必须转义，CSV 对 `= + - @` 开头单元格加前缀，文件名只能使用服务端 artifact_id；
- 日志不写身份证件和 API Key；保存 Prompt、模型、权重、评分和人工修改审计。

M1：行业默认、PromptPack、C 双模式、双字幕、transcript、追问链和状态机。

M2：冻结 rubric、可复算 scorecard、成果文件、人工复核和 `interview-handoff-v1`。

M3：摄像头授权、分片媒体（本地校验/幂等/合并适配器）、断点续传、本地 ASR、技术事件和时间线。

M4：断线恢复、幂等、乱序重排、超时降级、风险复核、token 鉴权、定时保留清理、删除和公平性测试。

验收：过程先记录且提交后才评分；AI 只字幕提问；字幕可修订并可追溯媒体；追问可关联父问题；最终分数唯一可复算；摄像头事件不扣分；不输出确认作弊；人工复核后才交 D；无摄像头、ASR/网络失败均有降级路径。
