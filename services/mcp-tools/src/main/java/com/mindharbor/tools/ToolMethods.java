package com.mindharbor.tools;

import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class ToolMethods {
    private final ExcelTool excel;
    private final MailTool mail;
    public ToolMethods(ExcelTool excel,MailTool mail){this.excel=excel;this.mail=mail;}

    @Tool(name="mindharbor_write_excel_report",description="Archive one report in Excel. Idempotent by reportId.")
    public Map<String,Object> writeExcelReport(Long reportId,Long userId,String username,String sessionId,
            String intent,String emotion,double emotionScore,String riskLevel,double confidence,
            String summary,String content,String createdAt) {
        return excel.write(reportId,Arrays.asList(String.valueOf(reportId),String.valueOf(userId),username,sessionId,
                intent,emotion,String.valueOf(emotionScore),riskLevel,String.valueOf(confidence),summary,content,createdAt));
    }
    @Tool(name="mindharbor_send_risk_alert",description="Send an immutable mail task through Resend. Reuse the same key and payload for retries.")
    public Map<String,Object> sendRiskAlert(Long taskId,Long reportId,Long alertId,int reminderRound,
            String idempotencyKey,String firstSendAt,String payloadJson) {
        return mail.send(taskId,reportId,alertId,reminderRound,idempotencyKey,firstSendAt,payloadJson);
    }
    @Tool(name="mindharbor_get_mail_status",description="Query delivery status of a previously submitted mail task.")
    public Map<String,Object> getMailStatus(String providerEmailId) {return mail.delivery(providerEmailId);}
    @Tool(name="mindharbor_get_mail_task",description="Recover a persisted tool execution result by its idempotency key. Does not send mail.")
    public Map<String,Object> getMailTask(String idempotencyKey) {return mail.result(idempotencyKey);}
}
