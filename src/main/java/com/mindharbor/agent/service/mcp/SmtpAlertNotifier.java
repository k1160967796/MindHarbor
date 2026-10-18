package com.mindharbor.agent.service.mcp;

import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.AlertRecord;
import com.mindharbor.agent.domain.PsychologicalReport;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 邮件预警实现。
 *
 * <p>高风险报告触发后，把摘要信息发送给配置的辅导员或心理中心邮箱。</p>
 */
public class SmtpAlertNotifier implements AlertNotifier {

    private final JavaMailSender mailSender;
    private final MindHarborProperties properties;

    public SmtpAlertNotifier(JavaMailSender mailSender, MindHarborProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void notifyFrozen(com.mindharbor.agent.domain.ReportTask task, AlertRecord alert, PsychologicalReport report) {
        try {
            var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(task.mailPayload);
            var message=mailSender.createMimeMessage();
            var helper=new org.springframework.mail.javamail.MimeMessageHelper(message,"UTF-8");
            helper.setFrom(payload.path("from").asText());
            helper.setTo(payload.path("to").get(0).asText());
            helper.setSubject(payload.path("subject").asText());
            helper.setText(payload.path("text").asText(),false);
            message.setHeader("X-MindHarbor-Task-ID",task.idempotencyKey);
            mailSender.send(message);
        } catch(org.springframework.mail.MailException ex){throw ex;}
        catch(Exception ex){throw new IllegalStateException("Cannot send frozen mail",ex);}
    }

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMcp().getEmail().getFrom());
        message.setTo(alertRecord.getRecipient());
        message.setSubject("【高危心理预警】学生用户 %s 存在高风险信号".formatted(report.getUser().getUsername()));
        message.setText("""
                系统在对话中监测到 1 名学生出现高风险心理状态，请及时关注并干预。

                【预警信息如下】
                报告ID：%s
                用户ID：%s
                学生：%s
                对话内容：%s
                情绪判定：%s
                综合情绪得分：%.2f
                风险等级：%s
                判断摘要：%s

                """.formatted(
                report.getId(),
                report.getUser().getUsername(),
                report.getUser().getDisplayName(),
                report.getContent(),
                report.getEmotion(),
                report.getEmotionScore(),
                report.getRiskLevel(),
                report.getSummary()));
        mailSender.send(message);
    }
}
