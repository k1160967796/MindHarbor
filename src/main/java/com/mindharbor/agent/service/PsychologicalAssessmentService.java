package com.mindharbor.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.EmotionLabel;
import com.mindharbor.agent.domain.RiskLevel;
import com.mindharbor.agent.service.ai.AiClient;
import com.mindharbor.agent.service.ai.AiMessage;
import com.mindharbor.agent.service.ai.PromptTemplates;
import com.mindharbor.agent.service.ai.RiskLexicon;
import java.util.List;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

@Service
/**
 * 后台心理状态评估服务。
 *
 * <p>结合高风险词库、模型结构化输出和关键词兜底，生成报告所需的情绪与风险字段。</p>
 */
public class PsychologicalAssessmentService {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public PsychologicalAssessmentService(AiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public PsychologyAssessment assess(String input) {
        return assess(input, List.of());
    }

    public PsychologyAssessment assess(String input, List<AiMessage> history) {
        // 高风险词库是硬规则，优先于模型判断，保证明显自伤/伤人信号不会被漏掉。
        if (RiskLexicon.hasHighRiskSignal(input.toLowerCase())) {
            return new PsychologyAssessment(
                    EmotionLabel.HIGH_RISK,
                    4.0,
                    RiskLevel.HIGH,
                    0.95,
                    "Explicit high-risk signal detected.");
        }
        try {
            boolean recentHighRisk = RiskLexicon.hasRecentHighRiskUserSignal(history);
            List<AiMessage> messages = new ArrayList<>(PromptTemplates.psychologyPrompt(history, input));
            if (recentHighRisk) {
                messages.add(1, AiMessage.system("""
                        服务端已检测到近期用户明确高风险表达，本轮必须复核其是否已缓解。
                        JSON 额外返回 recentRiskResolved（布尔值）和 resolutionEvidence（字符串）。
                        只有当前用户明确提供安全改善依据，才可设为 true；依据必须逐字摘自当前输入，至少4个字符。
                        仅解释作业压力、换话题或未重复危险词都不构成缓解依据，此时 false，依据为空字符串。
                        若无法确认缓解，应保守评估风险。不能把助手的话当作用户的安全证明。
                        """));
            }
            String raw = aiClient.complete(messages);
            JsonNode json = assessmentJson(raw);
            PsychologyAssessment assessment = normalize(parseJson(json));
            if (recentHighRisk && assessment.risk() != RiskLevel.HIGH && !hasResolutionEvidence(json, input)) {
                return new PsychologyAssessment(EmotionLabel.HIGH_RISK, 4.0, RiskLevel.HIGH, assessment.confidence(),
                        "Recent explicit high-risk signal; current response provides no verified resolution evidence. Conservative review required.");
            }
            return assessment;
        } catch (Exception ignored) {
            if (RiskLexicon.hasRecentHighRiskUserSignal(history)) {
                return new PsychologyAssessment(EmotionLabel.HIGH_RISK, 4.0, RiskLevel.HIGH, 0.5,
                        "Recent explicit high-risk signal; reassessment unavailable. Conservative fallback, not a new model assessment.");
            }
            // 模型输出格式异常或调用失败时，使用关键词兜底，保证报告链路仍可运行。
            return heuristic(input);
        }
    }

    private JsonNode assessmentJson(String raw) throws Exception {
        String json = raw.trim();
        // 兼容模型在 JSON 前后额外输出少量文本的情况，只截取最外层 JSON 对象。
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        return objectMapper.readTree(json);
    }

    private boolean hasResolutionEvidence(JsonNode node, String input) {
        JsonNode resolved = node.path("recentRiskResolved");
        JsonNode evidence = node.path("resolutionEvidence");
        return resolved.isBoolean() && resolved.booleanValue() && evidence.isTextual()
                && evidence.asText().strip().length() >= 4 && input.contains(evidence.asText().strip());
    }

    private PsychologyAssessment parseJson(JsonNode node) {
        // 缺失分类字段不能当成一次成功的 LOW 评估。
        if (node == null || !node.isObject() || !node.path("emotion").isTextual()
                || !node.path("risk").isTextual()) {
            throw new IllegalArgumentException("Missing assessment labels");
        }
        EmotionLabel emotion = EmotionLabel.valueOf(node.path("emotion").asText("NORMAL").toUpperCase());
        double score = node.path("emotionScore").asDouble(scoreForEmotion(emotion));
        RiskLevel risk = RiskLevel.valueOf(node.path("risk").asText(riskFromScore(score).name()).toUpperCase());
        double confidence = node.path("confidence").asDouble(0.75);
        String summary = node.path("summary").asText("Model assessment.");
        return new PsychologyAssessment(emotion, score, risk, confidence, summary);
    }

    private PsychologyAssessment normalize(PsychologyAssessment assessment) {
        // 风险等级取模型等级和分数推导等级中更高的一方，降低低估风险的概率。
        RiskLevel scoreRisk = riskFromScore(assessment.emotionScore());
        RiskLevel risk = assessment.risk().ordinal() > scoreRisk.ordinal() ? assessment.risk() : scoreRisk;
        if (assessment.emotion() == EmotionLabel.HIGH_RISK) {
            risk = RiskLevel.HIGH;
        }
        return new PsychologyAssessment(
                assessment.emotion(),
                assessment.emotionScore(),
                risk,
                clamp(assessment.confidence(), 0.0, 1.0),
                assessment.summary());
    }

    private PsychologyAssessment heuristic(String input) {
        String normalized = input.toLowerCase();
        if (containsAny(normalized, "抑郁", "低落", "压抑", "崩溃", "难过", "depress", "hopeless")) {
            return new PsychologyAssessment(EmotionLabel.DEPRESSED, 3.1, RiskLevel.MEDIUM, 0.75, "Low mood keywords detected.");
        }
        if (containsAny(normalized, "焦虑", "压力", "睡不着", "失眠", "anxious", "stress", "insomnia")) {
            return new PsychologyAssessment(EmotionLabel.ANXIETY, 2.2, RiskLevel.LOW, 0.72, "Anxiety or pressure keywords detected.");
        }
        return new PsychologyAssessment(EmotionLabel.NORMAL, 0.0, RiskLevel.LOW, 0.66, "No obvious risk signal.");
    }

    private RiskLevel riskFromScore(double score) {
        if (score >= 4.0) {
            return RiskLevel.HIGH;
        }
        if (score >= 3.0) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private double scoreForEmotion(EmotionLabel emotion) {
        return switch (emotion) {
            case HIGH_RISK -> 4.0;
            case DEPRESSED -> 3.0;
            case ANXIETY -> 2.0;
            case NORMAL -> 0.0;
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
