package com.hragent.agent.interviewer.artifact;

import com.hragent.tool.AgentTools;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ArtifactService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);
    public List<Map<String, Object>> writePlan(String sessionId, Map<String, Object> plan) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        try {
            String html = "<!doctype html><meta charset=\"utf-8\"><title>面试题纲</title><pre>"
                    + escape(String.valueOf(plan)) + "</pre>";
            artifacts.add(artifact("plan", AgentTools.writeDoc("html", "interview_" + safeId(sessionId) + "_plan.html", html)));
        } catch (Exception e) {
            log.error("面试题纲成果生成失败，sessionId={}", sessionId, e);
            artifacts.add(failedArtifact("plan", e));
        }
        return artifacts;
    }

    public List<Map<String, Object>> writeMinutes(String sessionId, Map<String, Object> minutes,
                                                  Map<String, Object> scorecard) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        try {
            artifacts.add(artifact("minutes", AgentTools.writeDoc("md", "interview_" + safeId(sessionId) + "_minutes.md",
                    AgentTools.toMarkdown("面试纪要", minutes))));
            List<Map<String, Object>> dimensions = maps(scorecard.get("dimensions"));
            StringBuilder csv = new StringBuilder("dimension,weight,score,status,evidence_refs\n");
            for (Map<String, Object> row : dimensions) {
                csv.append(csv(row.get("dimension"))).append(',')
                        .append(csv(row.get("weight"))).append(',')
                        .append(csv(row.get("score"))).append(',')
                        .append(csv(row.get("status"))).append(',')
                        .append(csv(row.get("evidence_refs"))).append('\n');
            }
            artifacts.add(artifact("scores", AgentTools.writeDoc("csv", "interview_" + safeId(sessionId) + "_scores.csv", csv.toString())));
        } catch (Exception e) {
            log.error("面试纪要成果生成失败，sessionId={}", sessionId, e);
            artifacts.add(failedArtifact("minutes", e));
        }
        return artifacts;
    }

    private Map<String, Object> artifact(String type, Map<String, Object> result) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("type", type);
        artifact.put("filename", result.get("filename"));
        artifact.put("path", result.get("path"));
        return artifact;
    }

    private Map<String, Object> failedArtifact(String type, Exception e) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("type", type);
        artifact.put("status", "failed");
        artifact.put("error", e.getClass().getSimpleName());
        return artifact;
    }

    private String safeId(String id) { return id.replaceAll("[^A-Za-z0-9_-]", "_"); }
    private String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private String csv(Object value) {
        String text = String.valueOf(value == null ? "" : value);
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return result;
    }
}
