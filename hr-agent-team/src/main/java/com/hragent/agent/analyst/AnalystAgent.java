package com.hragent.agent.analyst;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 招聘分析师（成员 A 专属）· 技能 talent.analyze · 文件 agent/analyst/AnalystAgent.java
 *
 * 职责：把模糊招聘诉求 → 需求澄清(demand) + 结构化 JD(jd) + 候选人能力画像(persona)。
 *       无上游，产出交给下游 B(简历猎手)。
 *
 * run() 输出字段（严格照 SKILLS技能注册表 + all_skill 2.2 数据契约，不自创）：
 *   demand  { clarified, missing[], questions[] }                         // DemandClarified
 *   jd      { jd_id, title, responsibilities[], hard_requirements[], nice_to_have[] }  // JD
 *   persona { core_competencies[], soft_traits[], culture_fit_hint }      // Persona
 *   reason
 *
 * 并且 run() 内真调用工具 doc_writer 落成 3 份可验收成果文件：
 *   需求澄清说明.md（报告）、结构化JD.html（网页）、候选人能力画像.csv（表格）。
 */
public class AnalystAgent implements Agent {

    private static final String ROLE =
            "你是「招聘分析师」，招聘流程开头的数字员工，技能 id：talent.analyze。\n" +
            "职责：把业务方一句模糊的招聘诉求，澄清为结构化需求，并生成结构化 JD（岗位描述）与候选人能力画像。无上游，产出交给下游简历猎手（B）。\n" +
            "\n" +
            "输入字段：\n" +
            "- raw_demand：业务方原始招聘诉求（必填）\n" +
            "- context：可选，团队/项目背景\n" +
            "\n" +
            "你必须只输出一个合法 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹。字段与结构严格如下：\n" +
            "{\n" +
            "  demand: {\n" +
            "    clarified: 澄清后的完整需求陈述（字符串）,\n" +
            "    missing: 仍待确认的信息点（字符串数组）,\n" +
            "    questions: 生成给业务方的追问清单（字符串数组）\n" +
            "  },\n" +
            "  jd: {\n" +
            "    jd_id: 岗位唯一标识（字符串）,\n" +
            "    title: 岗位名称（字符串）,\n" +
            "    responsibilities: 岗位职责（字符串数组）,\n" +
            "    hard_requirements: 硬性要求，如学历/年限/必备技能（字符串数组）,\n" +
            "    nice_to_have: 加分项（字符串数组）\n" +
            "  },\n" +
            "  persona: {\n" +
            "    core_competencies: 核心能力（字符串数组）,\n" +
            "    soft_traits: 软素质/性格特质（字符串数组）,\n" +
            "    culture_fit_hint: 文化契合提示（字符串）\n" +
            "  },\n" +
            "  reason: 整份分析的可解释理由（字符串，四段式）\n" +
            "}\n" +
            "\n" +
            "硬性约束：\n" +
            "1. 字段名、层级、类型必须与上面完全一致，禁止自创字段、禁止缺字段、禁止返回 null；\n" +
            "2. 所有数组字段必须是字符串数组；\n" +
            "3. reason 与文字结论采用四段式：结论 → 依据 → 风险 → 下一步；\n" +
            "4. 禁止编造：信息不足时，把不确定项写进 demand.missing 与 demand.questions，绝不凭空补充；\n" +
            "5. 术语统一：用「候选人」（不用求职者/应聘者）、「岗位描述 JD」（不用招聘广告）、「能力画像」（不用人才画像）；\n" +
            "6. 本 agent 会用工具 doc_writer 把结果落成文件；落文件由代码在 run() 中调用，你只负责输出上面的 JSON。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = buildUserInput(payload);

            // 1) 调模型，要求返回符合契约字段的 JSON
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 2) 校验并规整字段，不合格就抛错（宁可失败，不硬编造）
            Map<String, Object> validated = validateAndNormalize(result);

            // 3) 🔧 真调用工具 doc_writer：把成果落成 3 份可验收文件（报告/网页/表格）
            writeDeliverables(validated);

            return validated;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("AnalystAgent 执行失败：" + e.getMessage(), e);
        }
    }

    /** 读取并校验输入，拼成给模型的 user 消息。 */
    private String buildUserInput(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("输入 payload 为空");
        }
        String rawDemand = asString(payload.get("raw_demand")).trim();
        if (rawDemand.isEmpty()) {
            throw new IllegalArgumentException("缺少必填输入字段 raw_demand");
        }
        String context = asString(payload.get("context")).trim();

        StringBuilder sb = new StringBuilder();
        sb.append("原始招聘诉求(raw_demand)：").append(rawDemand).append("\n");
        if (!context.isEmpty()) {
            sb.append("补充上下文(context)：").append(context).append("\n");
        }
        sb.append("请严格按系统提示词 ROLE 的要求，只输出一个合法 JSON 对象。");
        return sb.toString();
    }

    /** 按契约校验/规整模型输出；核心字段缺失时抛错，绝不用占位数据糊弄。 */
    private Map<String, Object> validateAndNormalize(Map<String, Object> result) {
        if (result == null) {
            throw new IllegalStateException("模型未返回结果");
        }

        // demand（DemandClarified）
        Map<String, Object> demand = asMap(result.get("demand"));
        String clarified = asString(demand.get("clarified")).trim();
        if (clarified.isEmpty()) {
            throw new IllegalStateException("模型输出缺少字段 demand.clarified");
        }
        Map<String, Object> demandOut = new LinkedHashMap<>();
        demandOut.put("clarified", clarified);
        demandOut.put("missing", asStringList(demand.get("missing")));
        demandOut.put("questions", asStringList(demand.get("questions")));

        // jd（JD）
        Map<String, Object> jd = asMap(result.get("jd"));
        String title = asString(jd.get("title")).trim();
        if (title.isEmpty()) {
            throw new IllegalStateException("模型输出缺少字段 jd.title");
        }
        String jdId = asString(jd.get("jd_id")).trim();
        if (jdId.isEmpty()) {
            jdId = "JD-" + UUID.randomUUID().toString().substring(0, 8);
        }
        Map<String, Object> jdOut = new LinkedHashMap<>();
        jdOut.put("jd_id", jdId);
        jdOut.put("title", title);
        jdOut.put("responsibilities", asStringList(jd.get("responsibilities")));
        jdOut.put("hard_requirements", asStringList(jd.get("hard_requirements")));
        jdOut.put("nice_to_have", asStringList(jd.get("nice_to_have")));

        // persona（Persona）
        Map<String, Object> persona = asMap(result.get("persona"));
        List<String> core = asStringList(persona.get("core_competencies"));
        if (core.isEmpty()) {
            throw new IllegalStateException("模型输出缺少字段 persona.core_competencies");
        }
        Map<String, Object> personaOut = new LinkedHashMap<>();
        personaOut.put("core_competencies", core);
        personaOut.put("soft_traits", asStringList(persona.get("soft_traits")));
        personaOut.put("culture_fit_hint", asString(persona.get("culture_fit_hint")).trim());

        // 顶层只保留契约规定的 4 个字段
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demand", demandOut);
        out.put("jd", jdOut);
        out.put("persona", personaOut);
        out.put("reason", asString(result.get("reason")).trim());
        return out;
    }

    /** 真调用 doc_writer，把三样成果分别落成文件。 */
    @SuppressWarnings("unchecked")
    private void writeDeliverables(Map<String, Object> result) throws Exception {
        Map<String, Object> demand = (Map<String, Object>) result.get("demand");
        Map<String, Object> jd = (Map<String, Object>) result.get("jd");
        Map<String, Object> persona = (Map<String, Object>) result.get("persona");

        // 需求澄清说明 → 报告(.md)
        AgentTools.writeDoc("md", "需求澄清说明.md", buildDemandReport(demand));
        // 结构化 JD → 网页(.html)
        AgentTools.writeDoc("html", "结构化JD.html", buildJdHtml(jd));
        // 候选人能力画像 → 表格(.csv)
        AgentTools.writeDoc("csv", "候选人能力画像.csv", buildPersonaCsv(persona));
    }

    private String buildDemandReport(Map<String, Object> demand) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 需求澄清说明\n\n");
        sb.append("## 澄清后需求陈述\n").append(demand.get("clarified")).append("\n\n");
        sb.append("## 待确认信息\n");
        for (String m : asStringList(demand.get("missing"))) {
            sb.append("- ").append(m).append("\n");
        }
        sb.append("\n## 追问清单\n");
        for (String q : asStringList(demand.get("questions"))) {
            sb.append("- ").append(q).append("\n");
        }
        return sb.toString();
    }

    private String buildJdHtml(Map<String, Object> jd) {
        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String sealImg = sealImageTag();
        String html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>@@TITLE@@ · 岗位描述(JD)</title>
              <style>
                :root{
                  --green:#5aa585; --green-soft:#eaf6f0;
                  --blue:#4a8fc2; --blue-soft:#e8f3fb;
                  --ink:#38474f; --muted:#7e8f98; --card:#ffffff;
                  --radius:18px;
                }
                *{box-sizing:border-box;margin:0;padding:0}
                body{
                  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
                  color:var(--ink);
                  background:linear-gradient(165deg,#e7f7ee 0%,#e9f2fb 48%,#eff6fc 100%);
                  background-attachment:fixed;
                  min-height:100vh;padding:34px 16px;line-height:1.75;
                  -webkit-font-smoothing:antialiased;
                }
                .page{position:relative;max-width:860px;margin:0 auto;background:var(--card);
                      border-radius:var(--radius);box-shadow:0 12px 40px rgba(60,120,120,.14);overflow:hidden}
                .hero{background:linear-gradient(135deg,#5aa585 0%,#7ec4a6 32%,#5ba6cf 72%,#4a8fc2 100%);
                      color:#fff;padding:42px 48px 36px}
                .hero .id-tag{display:inline-block;font-size:12px;letter-spacing:1.4px;
                      background:rgba(255,255,255,.2);padding:5px 14px;border-radius:999px;margin-bottom:18px}
                .hero h1{font-size:32px;font-weight:600;letter-spacing:.5px;line-height:1.3}
                .hero .sub{font-size:14px;opacity:.94;margin-top:10px}
                .body{padding:38px 48px 26px}
                section{margin-bottom:32px}
                h2{font-size:16px;font-weight:600;display:flex;align-items:center;gap:11px;
                      color:var(--ink);margin-bottom:4px}
                h2 .dot{width:11px;height:11px;border-radius:50%;flex:0 0 auto}
                h2 .d-green{background:var(--green);box-shadow:0 0 0 4px var(--green-soft)}
                h2 .d-blue{background:var(--blue);box-shadow:0 0 0 4px var(--blue-soft)}
                .hint{font-size:13px;color:var(--muted);margin:0 0 12px 24px}
                ul{list-style:none}
                li{position:relative;padding:8px 4px 8px 28px;color:#42545e}
                li::before{content:"";position:absolute;left:4px;top:17px;width:8px;height:8px;border-radius:2px;
                     background:linear-gradient(135deg,var(--green),var(--blue));opacity:.8}
                .empty{color:var(--muted);font-size:13px;padding-left:24px}
                footer{padding:22px 48px 30px;text-align:center;color:#9aabb3;font-size:12px;
                      letter-spacing:.5px;border-top:1px solid #eef2f4}
                .seal{position:absolute;right:30px;bottom:26px;width:192px;pointer-events:none;
                      transform:rotate(-12deg);opacity:.92;z-index:5;
                      filter:drop-shadow(0 4px 8px rgba(0,0,0,.14))}
              </style>
            </head>
            <body>
              <div class="page">
                <header class="hero">
                  <div class="id-tag">岗位 ID · @@JDID@@</div>
                  <h1>@@TITLE@@</h1>
                  <p class="sub">结构化岗位描述（JD）· 招聘分析师 talent.analyze</p>
                </header>
                <div class="body">
                  <section>
                    <h2><span class="dot d-green"></span>岗位职责</h2>
                    <p class="hint">这份岗位需要承担的主要工作</p>
                    @@RESP@@
                  </section>
                  <section>
                    <h2><span class="dot d-blue"></span>硬性要求</h2>
                    <p class="hint">候选人必须满足的门槛条件</p>
                    @@HARD@@
                  </section>
                  <section>
                    <h2><span class="dot d-green"></span>加分项</h2>
                    <p class="hint">优先考虑、但不作硬性门槛</p>
                    @@NICE@@
                  </section>
                </div>
                <footer>生成时间：@@TIME@@　·　结构化 JD · 招聘分析师 talent.analyze</footer>
                @@SEALIMG@@
              </div>
            </body>
            </html>
            """;
        return html
                .replace("@@JDID@@", esc(asString(jd.get("jd_id"))))
                .replace("@@TITLE@@", esc(asString(jd.get("title"))))
                .replace("@@RESP@@", listHtml(asStringList(jd.get("responsibilities"))))
                .replace("@@HARD@@", listHtml(asStringList(jd.get("hard_requirements"))))
                .replace("@@NICE@@", listHtml(asStringList(jd.get("nice_to_have"))))
                .replace("@@TIME@@", esc(generatedAt))
                .replace("@@SEALIMG@@", sealImg);
    }

    private String buildPersonaCsv(Map<String, Object> persona) {
        String core = csvCell(String.join("；", asStringList(persona.get("core_competencies"))));
        String soft = csvCell(String.join("；", asStringList(persona.get("soft_traits"))));
        String culture = csvCell(asString(persona.get("culture_fit_hint")));
        // 开头加 UTF-8 BOM：否则 Excel 会按本地 GBK 解码，中文乱码
        return "\uFEFFcore_competencies,soft_traits,culture_fit_hint\n"
                + core + "," + soft + "," + culture + "\n";
    }

    // ---------- 工具型辅助方法 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<>();
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 把条目列表渲染成 <ul><li>…</li></ul>；空列表给占位提示。 */
    private static String listHtml(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "<p class=\"empty\">— 暂未列出 —</p>";
        }
        StringBuilder sb = new StringBuilder("<ul>");
        for (String item : items) {
            sb.append("<li>").append(esc(item)).append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    /** HTML 转义，防止内容里的特殊字符破坏页面结构。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 读取右下角印章图片并返回 <img>；找不到就返回空串，不影响页面。 */
    private static String sealImageTag() {
        String src = sealDataUri();
        if (src == null || src.isEmpty()) {
            return "";
        }
        return "<img class=\"seal\" src=\"" + src + "\" alt=\"印章\">";
    }

    /** 把印章 PNG 读成 base64 data URI，离线自包含展示；找不到返回空串。 */
    private static String sealDataUri() {
        String env = System.getenv("HR_SEAL_PATH");
        String path = (env == null || env.isBlank()) ? "img/seal_custom.png" : env;
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    // 自测：右键运行 main（需先配好 DEEPSEEK_API_KEY）
    public static void main(String[] args) {
        AnalystAgent agent = new AnalystAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("raw_demand", "诺贝尔物理学奖");
        System.out.println(agent.run(sample));
    }
}
