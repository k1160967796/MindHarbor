package com.mindharbor.agent.dto;

import com.mindharbor.agent.domain.EmotionLabel;
import com.mindharbor.agent.domain.IntentType;
import com.mindharbor.agent.domain.PsychologicalReport;
import com.mindharbor.agent.domain.RiskLevel;
import com.mindharbor.agent.domain.ToolStatus;
import java.time.Instant;

/**
 * 后台心理报告列表响应。
 */
public record ReportResponse(
        Long id,
        Long userId,
        String username,
        String sessionId,
        IntentType intent,
        EmotionLabel emotion,
        double emotionScore,
        RiskLevel riskLevel,
        double confidence,
        String summary,
        ToolStatus excelStatus,
        ToolStatus emailStatus,
        Instant createdAt
) {
    public static ReportResponse from(PsychologicalReport report) {
        return new ReportResponse(
                report.getId(),
                report.getUser().getId(),
                report.getUser().getUsername(),
                report.getSession() == null ? null : report.getSession().getPublicId(),
                report.getIntent(),
                report.getEmotion(),
                report.getEmotionScore(),
                report.getRiskLevel(),
                report.getConfidence(),
                report.getSummary(),
                report.getExcelStatus(),
                report.getEmailStatus(),
                report.getCreatedAt());
    }
}
