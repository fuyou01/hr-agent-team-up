package com.hragent.agent.scout;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历猎手（成员 B 专属）· 技能 resume.screen · 文件 agent/scout/ScoutAgent.java
 *
 * 职责：拿 A 的 JD，读候选人简历 → 结构化(resume) → 按 JD 打分。上游 A，下游 C(面试官)。
 *
 * 开发说明（已定稿，成员 B 只需微调 ROLE 文案/样例）：
 *  run() = 🔧 resume_parser 解析简历 → 调模型(输出 resume + score...) → 校验 →
 *          🔧 doc_writer 落两份成果表(resume_parsed.csv + scout_score.csv) → 返回 JSON。
 */
public class ScoutAgent implements Agent {

    // TODO(成员 B)：可继续按你的判断微调，但"输出字段"不要改（见手册第1节）。
    private static final String ROLE =
            "你是简历猎手，招聘流程第二步的数字员工。\n" +
            "你会用到的工具：resume_parser（解析简历文本为结构化）、doc_writer（把结果写成表格文件）。\n" +
            "职责：根据 JD，先把候选人简历结构化为 resume，再按 JD 做匹配打分。\n" +
            "输入字段：jd（结构化岗位）、resume（简历文本，可含 resumeParsed 初步解析）。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - resume: { name, years, skills[], experiences[], education }\n" +
            "  - score: 0-100 的整数\n" +
            "  - verdict: 只能 shortlist / hold / reject\n" +
            "  - matched: [{skill, evidence}] 命中的技能与证据\n" +
            "  - gaps: [] 缺口\n" +
            "  - reason\n" +
            "风格：先结论、后依据、再风险、再下一步；禁止编造证据，不确定就 verdict=hold 并说明。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            // 0) 🔧 真调用工具 resume_parser：简历是文本则先解析
            Object resumeRaw = payload.get("resume");
            Map<String, Object> parsedBasic = new LinkedHashMap<>();
            if (resumeRaw != null && resumeRaw instanceof String) {
                parsedBasic = AgentTools.parseResume(resumeRaw.toString());
                payload = new LinkedHashMap<>(payload);
                payload.put("resumeParsed", parsedBasic);
            }

            // 1) 调模型：让它输出 resume 结构 + 打分
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 若模型没给 resume，用初步解析结果兜底
            if (result.get("resume") == null) {
                result.put("resume", parsedBasic);
            }

            // 2) 🔧 真调用 doc_writer：落两份可验收成果表
            @SuppressWarnings("unchecked")
            Map<String, Object> resumeOut = (Map<String, Object>) result.get("resume");
            AgentTools.writeDoc("csv", "resume_parsed.csv",
                    "name,years,skills,experiences,education\n"
                    + csvVal(resumeOut.get("name")) + "," + csvVal(resumeOut.get("years")) + ","
                    + csvVal(resumeOut.get("skills")) + "," + csvVal(resumeOut.get("experiences")) + ","
                    + csvVal(resumeOut.get("education")));
            AgentTools.writeDoc("csv", "scout_score.csv",
                    "score,verdict,matched,gaps,reason\n"
                    + csvVal(result.get("score")) + "," + csvVal(result.get("verdict")) + ","
                    + csvVal(result.get("matched")) + "," + csvVal(result.get("gaps")) + ","
                    + csvVal(result.get("reason")));

            // 3) 校验 verdict 枚举 / score 范围
            validateVerdict(String.valueOf(result.get("verdict")));
            validateScore(result.get("score"));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ScoutAgent 执行失败", e);
        }
    }

    private void validateVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()
                || !("shortlist".equals(verdict) || "hold".equals(verdict) || "reject".equals(verdict))) {
            throw new IllegalStateException("verdict 非法（须 shortlist/hold/reject）：" + verdict);
        }
    }

    private void validateScore(Object score) {
        if (score == null) {
            throw new IllegalStateException("缺少 score");
        }
        int s;
        try {
            s = Integer.parseInt(String.valueOf(score));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("score 不是整数：" + score);
        }
        if (s < 0 || s > 100) {
            throw new IllegalStateException("score 超出 0-100：" + s);
        }
    }

    private static String csvVal(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o);
        return s.replace("\r", " ").replace("\n", " ").replace(",", "；");
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        ScoutAgent agent = new ScoutAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("jd", Map.of("title", "数据分析师",
                "hard_requirements", new String[]{"Python", "SQL", "数据分析经验"}));
        sample.put("resume", "张三\n5年数据分析经验\n技能：python、sql、报表搭建");
        System.out.println(agent.run(sample));
    }
}
