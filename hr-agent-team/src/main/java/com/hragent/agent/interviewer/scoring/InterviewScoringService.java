package com.hragent.agent.interviewer.scoring;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InterviewScoringService {
    public Map<String, Object> score(Map<String, Object> minutes, Map<String, Object> promptPack,
                                     Map<String, String> questionDimensions) {
        Map<String, Object> minutesMap = minutes == null ? Map.of() : minutes;
        List<Map<String, Object>> rubric = castMaps(promptPack == null ? null : promptPack.get("rubric"));
        List<Map<String, Object>> qa = castMaps(minutesMap.get("qa"));
        List<Map<String, Object>> dimensions = new ArrayList<>();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> rule : rubric) {
            grouped.put(String.valueOf(rule.getOrDefault("dimension", "未命名维度")), new ArrayList<>());
        }
        int qaIndex = 0;
        for (Map<String, Object> item : qa) {
            int itemScore = number(item.get("score"), -1);
            if (itemScore < 0 || itemScore > 100 || rubric.isEmpty()) continue;
            String question = String.valueOf(item.getOrDefault("question", ""));
            String mapped = String.valueOf(item.getOrDefault("dimension", ""));
            if (mapped.isBlank() && questionDimensions != null) mapped = questionDimensions.getOrDefault(question, "");
            if (!grouped.containsKey(mapped)) {
                mapped = String.valueOf(rubric.get(qaIndex % rubric.size()).getOrDefault("dimension", "未命名维度"));
            }
            grouped.get(mapped).add(item);
            qaIndex++;
        }
        int totalWeight = 0;
        double weighted = 0;
        boolean insufficientEvidence = false;
        for (Map<String, Object> rule : rubric) {
            String dimension = String.valueOf(rule.getOrDefault("dimension", "未命名维度"));
            int weight = number(rule.get("weight"), 0);
            List<Integer> scores = new ArrayList<>();
            List<String> evidence = new ArrayList<>();
            for (Map<String, Object> item : grouped.getOrDefault(dimension, List.of())) {
                int score = number(item.get("score"), -1);
                if (score >= 0 && score <= 100) {
                    scores.add(score);
                    String question = String.valueOf(item.getOrDefault("question", ""));
                    evidence.add(question.isBlank() ? "qa" : question);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dimension", dimension);
            row.put("weight", weight);
            row.put("evidence_refs", evidence);
            if (scores.isEmpty()) {
                insufficientEvidence = true;
                row.put("score", null);
                row.put("status", "insufficient_evidence");
                row.put("confidence", 0.0);
            } else {
                int avg = (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
                row.put("score", avg);
                row.put("status", "valid");
                row.put("confidence", Math.min(1.0, scores.size() / 3.0));
                weighted += avg * weight / 100.0;
                totalWeight += weight;
            }
            dimensions.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rubric_version", promptPack == null ? "unknown" : promptPack.getOrDefault("prompt_pack_version", "unknown"));
        result.put("dimensions", dimensions);
        result.put("overall_score", insufficientEvidence || totalWeight == 0 ? null : (int) Math.round(weighted));
        result.put("final_score", null);
        result.put("review_status", "pending");
        result.put("score_source", "minutes.qa + frozen rubric; camera events excluded");
        return result;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }
}
