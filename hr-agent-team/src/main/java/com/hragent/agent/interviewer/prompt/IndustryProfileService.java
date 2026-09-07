package com.hragent.agent.interviewer.prompt;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class IndustryProfileService {
    private static final Set<String> SUPPORTED = Set.of(
            "computer", "sales", "finance", "legal", "manufacturing",
            "supply_chain", "design", "marketing");

    public boolean supports(String industry) {
        return industry != null && SUPPORTED.contains(industry);
    }

    public Map<String, Object> profile(String industry) {
        String normalized = supports(industry) ? industry : "computer";
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("industry", normalized);
        profile.put("profile_version", normalized + "-v1");
        profile.put("common_questions", List.of(
                "请概括你最核心的三项可迁移能力，并说明如何解决本岗位最大痛点。",
                "请描述一次高压或资源匮乏下仍完成目标的经历。",
                "你过去离职决策的主要驱动因素是什么？期望什么样的工作环境？"));
        profile.put("online_questions", List.of(
                "远程推进项目时同事两天不回复，你会如何按时间梯度升级？",
                "如果面试中途掉线且无法重连，你的备用方案是什么？"));
        profile.put("reverse_question", "关于这个岗位前三个月，最大的非技术性挑战是什么？");
        profile.put("specialized_questions", specialized(normalized));
        profile.put("rubric_limits", List.of(
                Map.of("dimension", "岗位专业能力", "min", 20, "max", 50),
                Map.of("dimension", "问题拆解与逻辑", "min", 10, "max", 35),
                Map.of("dimension", "项目真实性与细节", "min", 10, "max", 30),
                Map.of("dimension", "抗压与复原力", "min", 5, "max", 20),
                Map.of("dimension", "远程协作与自我管理", "min", 5, "max", 20)));
        return profile;
    }

    private List<String> specialized(String industry) {
        return switch (industry) {
            case "sales" -> List.of("客户全程只打字时，你连续三句开场如何建立信任？");
            case "finance", "legal" -> List.of("面对异常数据或合规压力时，你如何定位风险并留下可审计记录？");
            case "manufacturing", "supply_chain" -> List.of("关键供应或工程风险出现时，你如何核验事实并降低返工/延误？");
            case "design", "marketing" -> List.of("请说明一次失败方案的反馈、修改和效果验证过程。");
            default -> List.of("请讲一个你负责过的最复杂系统或项目，并说明两个最关键的技术/业务决策。",
                    "如果线上系统流量突然提升十倍，你会按什么顺序定位和处理瓶颈？");
        };
    }
}
