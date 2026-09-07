package com.hragent.agent.interviewer.media;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 无 Key 的技术状态分析器；只处理设备/页面遥测，不做人脸、身份或情绪识别。 */
@Service
public class LocalVideoEventAnalyzer {
    public List<Map<String, Object>> analyze(Map<String, Object> telemetry) {
        Map<String, Object> input = telemetry == null ? Map.of() : telemetry;
        List<Map<String, Object>> events = new ArrayList<>();
        if (Boolean.FALSE.equals(input.get("camera_active"))) {
            events.add(event("camera_off", "medium", 1.0, "camera_active=false"));
        }
        int people = number(input.get("person_count"));
        if (people > 1) events.add(event("multiple_people_detected", "high", 0.9, "person_count=" + people));
        if (people == 0 && Boolean.TRUE.equals(input.get("camera_active"))) {
            events.add(event("no_person_in_frame", "medium", 0.8, "person_count=0"));
        }
        if (Boolean.FALSE.equals(input.get("tab_visible"))) {
            events.add(event("tab_hidden", "medium", 1.0, "tab_visible=false"));
        }
        if (Boolean.TRUE.equals(input.get("microphone_muted"))) {
            events.add(event("microphone_muted", "low", 1.0, "microphone_muted=true"));
        }
        if (Boolean.TRUE.equals(input.get("screen_share_stopped"))) {
            events.add(event("screen_share_stopped", "medium", 1.0, "screen_share_stopped=true"));
        }
        return events;
    }

    private Map<String, Object> event(String name, String severity, double confidence, String evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event_name", name);
        result.put("severity", severity);
        result.put("confidence", confidence);
        result.put("model_version", "local-rules-v1");
        result.put("evidence_ref", evidence);
        result.put("review_required", true);
        result.put("scoring_eligible", false);
        return result;
    }

    private int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : -1; }
}
