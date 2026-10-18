package com.mindharbor.agent.service;

import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.service.mcp.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ToolOrchestrationService {
    private final ExcelReportWriter excel;
    private final PsychologicalReportRepository reports;
    private final AlertRecordRepository alerts;
    private final ReportTaskRepository tasks;
    private final MindHarborProperties properties;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final MailWorkflowService mailWorkflow;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolOrchestrationService.class);

    public ToolOrchestrationService(ExcelReportWriter excel,
            PsychologicalReportRepository reports, AlertRecordRepository alerts,
            MindHarborProperties properties, @Qualifier("mcpTaskExecutor") TaskExecutor executor,
            TransactionTemplate tx, ReportTaskRepository tasks, EntityManager em, MailWorkflowService mailWorkflow) {
        this.excel=excel; this.reports=reports; this.alerts=alerts;
        this.properties=properties; this.executor=executor; this.tx=tx; this.tasks=tasks; this.em=em;
        this.mailWorkflow=mailWorkflow;
    }

    public void enqueue(Long reportId) {
        tx.executeWithoutResult(s -> {
            PsychologicalReport report = em.find(PsychologicalReport.class, reportId, LockModeType.PESSIMISTIC_WRITE);
            if (report == null) throw new IllegalArgumentException("Report not found");
            if (!tasks.findByReportId(reportId).isEmpty()) return;
            ReportTask excelTask = new ReportTask(); excelTask.reportId=reportId; excelTask.kind="EXCEL";
            tasks.save(excelTask);
            if (report.getRiskLevel() == RiskLevel.HIGH) {
                report.setEmailStatus(ToolStatus.PENDING);
                for (String recipient : new LinkedHashSet<>(properties.getMcp().getEmail().getRecipients())) {
                    AlertRecord alert = new AlertRecord(); alert.setReport(report); alert.setRecipient(recipient); alerts.save(alert);
                    mailWorkflow.initialize(alert);
                    ReportTask task = new ReportTask(); task.reportId=reportId; task.kind="MAIL";
                    task.recipient=recipient; task.alertId=alert.getId(); tasks.save(task);
                }
            }
        });
    }

    public void handleAsync(Long reportId) { enqueue(reportId); }
    public void handle(Long reportId) {
        enqueue(reportId);
        for (ReportTask task : tasks.findByReportId(reportId)) execute(task.id);
    }

    @Scheduled(fixedDelayString = "${mindharbor.tools.poll-ms:2000}", initialDelayString = "${mindharbor.tools.initial-delay-ms:3000}")
    public void poll() {
        for (Long id : tasks.due(Instant.now(), PageRequest.of(0, 32))) {
            try { executor.execute(() -> execute(id)); }
            catch (org.springframework.core.task.TaskRejectedException ex) { break; } // Still durable and pending.
        }
    }

    private record Work(ReportTask task, PsychologicalReport report, String owner) {}
    private Work claim(Long id) {
        return tx.execute(s -> {
            ReportTask task = tasks.lock(id).orElse(null);
            if (task == null) return null;
            if ("RUNNING".equals(task.status)) {
                if (task.leaseUntil.isAfter(Instant.now())) return null;
                task.status="RETRYING";
            }
            if (!Set.of("PENDING", "RETRYING").contains(task.status) || task.nextAttempt.isAfter(Instant.now())) return null;
            if (task.attempts >= 3) { task.status="FAILED"; updateResult(task); return null; }
            task.status="RUNNING"; task.owner=UUID.randomUUID().toString(); task.attempts++;
            task.leaseUntil=Instant.now().plusSeconds(180);
            PsychologicalReport report=reports.findById(task.reportId).orElseThrow();
            report.getUser().getUsername(); report.getUser().getDisplayName();
            if (report.getSession()!=null) report.getSession().getPublicId();
            return new Work(task, report, task.owner);
        });
    }

    public void execute(Long id) {
        ReportTask candidate=tasks.findById(id).orElse(null);
        if(candidate!=null && "MAIL".equals(candidate.kind)) { mailWorkflow.execute(id); return; }
        Work work;
        try { work=claim(id); } catch (Exception ex) { log.warn("Cannot claim tool task {}", id, ex); return; }
        if (work==null) return;
        Exception failure=null;
        try {
            excel.write(work.report);
        } catch (Exception ex) { failure=ex; }
        final Exception result=failure;
        try {
            tx.executeWithoutResult(s -> {
                ReportTask task=tasks.lock(id).orElseThrow();
                if (!Objects.equals(task.owner, work.owner) || !"RUNNING".equals(task.status)) return;
                if (result==null) { task.status="SUCCESS"; task.error=null; }
                else {
                    task.error=shorten(result.toString());
                    task.status=task.attempts>=3 ? "FAILED" : "RETRYING";
                    task.nextAttempt=Instant.now().plusSeconds(5L * task.attempts);
                }
                task.owner=null; task.leaseUntil=null;
                updateResult(task);
            });
        } catch (Exception ex) { log.error("Tool result persistence failed; lease recovery will reconcile task {}", id, ex); }
    }

    private void updateResult(ReportTask task) {
        PsychologicalReport report=em.find(PsychologicalReport.class, task.reportId, LockModeType.PESSIMISTIC_WRITE);
        ToolStatus status="SUCCESS".equals(task.status) ? ToolStatus.SUCCESS
                : Set.of("PENDING", "RETRYING", "RUNNING").contains(task.status) ? ToolStatus.PENDING : ToolStatus.FAILED;
        report.setExcelStatus(status);
        if (task.error!=null) report.setToolError(shorten(task.kind + ": " + task.status + " " + task.error));
    }
    private String shorten(String value) { return value==null ? "" : value.substring(0, Math.min(500,value.length())); }
}
