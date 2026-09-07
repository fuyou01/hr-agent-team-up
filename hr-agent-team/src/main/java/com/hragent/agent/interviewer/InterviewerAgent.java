package com.hragent.agent.interviewer;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试官（成员 C 专属）· 技能 interview.run · 文件 agent/interviewer/InterviewerAgent.java
 *
 * 职责：按 JD+简历生成面试题纲；根据问答记录生成纪要并给结论。上游 B，下游 D(测评背调员)。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 🔧 doc_writer 落文件 → 返回 JSON。
 * 输入含 transcript（问答记录）→ 出"纪要"；否则 → 出"题纲"。
 */
public class InterviewerAgent implements Agent {

    private static final String ROLE =
            "你是招聘流程第三步的线上面试官。\n" +
            "输入可能包含 industry、promptPack、jd、resume、transcript；promptPack 是本场冻结的行业题库和评分维度，必须优先遵守。\n" +
            "没有非空 transcript 时，只生成 plan：覆盖通用素质题、行业专业题、线上协作情景题和反向提问，并注明时长、考察目标和事实证据。\n" +
            "有非空 transcript 时，只生成 minutes：按问答总结评分，并根据回答中的项目、角色、技术、数字和决策提出至多两次引用式追问建议。\n" +
            "只能输出 JSON：plan{questions[]{text,intent},rubric[]{dimension,weight}} 或 minutes{summary,qa[]{question,answer,score},verdict}，以及 reason。\n" +
            "score 必须为 0-100 整数，verdict 只能 shortlist / hold / reject；证据不足使用 hold。\n" +
            "禁止编造，禁止根据外貌、表情、眼神、情绪、声音、人脸或视频状态评分；视频事件只能触发人工复核。\n" +
            "风格：结论→依据→风险→下一步。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
            Map<String, Object> result = client.callJson(ROLE, userInput);
            boolean hasTranscript = hasNonBlankTranscript(payload);
            validateResult(result, hasTranscript);

            // 🔧 真调用 doc_writer：把题纲/纪要落成成果文件
            AgentTools.writeDoc("md",
                    hasTranscript ? "interview_minutes.md" : "interview_plan.md",
                    AgentTools.toMarkdown(hasTranscript ? "面试纪要" : "面试题纲", result));

            return result;
        } catch (Exception e) {
            throw new RuntimeException("InterviewerAgent 执行失败", e);
        }
    }

    private boolean hasNonBlankTranscript(Map<String, Object> payload) {
        Object transcript = payload == null ? null : payload.get("transcript");
        return transcript != null && !String.valueOf(transcript).isBlank();
    }

    private void validateResult(Map<String, Object> result, boolean minutesMode) {
        if (result == null) throw new IllegalArgumentException("模型返回为空");
        String root = minutesMode ? "minutes" : "plan";
        if (!(result.get(root) instanceof Map<?, ?> value)) throw new IllegalArgumentException("缺少输出字段：" + root);
        if (minutesMode) {
            String verdict = String.valueOf(value.get("verdict"));
            if (!java.util.List.of("shortlist", "hold", "reject").contains(verdict)) {
                throw new IllegalArgumentException("非法 verdict：" + verdict);
            }
            if (!(value.get("qa") instanceof java.util.List<?> qa)) throw new IllegalArgumentException("minutes.qa 必须是数组");
            for (Object item : qa) {
                if (!(item instanceof Map<?, ?> row) || !(row.get("score") instanceof Number)
                        || ((Number) row.get("score")).doubleValue() % 1 != 0
                        || ((Number) row.get("score")).intValue() < 0 || ((Number) row.get("score")).intValue() > 100
                        || String.valueOf(row.get("answer") == null ? "" : row.get("answer")).isBlank()) {
                    throw new IllegalArgumentException("minutes.qa.score 必须是 0-100");
                }
            }
        } else {
            if (!(value.get("questions") instanceof java.util.List<?>) || !(value.get("rubric") instanceof java.util.List<?>)) {
                throw new IllegalArgumentException("plan.questions 和 plan.rubric 必须是数组");
            }
        }
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        InterviewerAgent agent = new InterviewerAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("jd", Map.of("title", "数据分析师"));
        sample.put("resume", Map.of("name", "张三", "skills", "python,sql"));
        sample.put("transcript", "Q1: 你做过哪些报表？A1: 电商日报、活动复盘。");
        System.out.println(agent.run(sample));
    }
}
