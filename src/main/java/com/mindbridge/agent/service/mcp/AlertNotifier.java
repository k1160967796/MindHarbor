package com.mindbridge.agent.service.mcp;

import com.mindbridge.agent.domain.AlertRecord;
import com.mindbridge.agent.domain.PsychologicalReport;

/**
 * 高风险预警通知接口。
 *
 * <p>具体实现可以是日志、SMTP 邮件或 HTTP MCP 服务。</p>
 */
public interface AlertNotifier {
    default void notifyFrozen(com.mindbridge.agent.domain.ReportTask task, AlertRecord alert, PsychologicalReport report) {
        notify(alert,report);
    }

    void notify(AlertRecord alertRecord, PsychologicalReport report);
}
