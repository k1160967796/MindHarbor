package com.mindharbor.agent.service;

import com.mindharbor.agent.config.MailWorkflowProperties;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.security.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MailWorkflowService {
    private final ReportTaskRepository tasks;
    private final AlertRecordRepository alerts;
    private final UserAccountRepository users;
    private final PsychologicalReportRepository reports;
    private final MailDeliveryEventRepository events;
    private final MailWorkflowProperties config;
    private final MailGateway gateway;
    private final TransactionTemplate tx;
    private final TaskExecutor executor;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MailWorkflowService.class);
    public MailWorkflowService(ReportTaskRepository tasks,AlertRecordRepository alerts,UserAccountRepository users,
            PsychologicalReportRepository reports,MailDeliveryEventRepository events,MailWorkflowProperties config,
            MailGateway gateway,TransactionTemplate tx,@Qualifier("mcpTaskExecutor") TaskExecutor executor,MailSchemaMigration migration) {
        this.tasks=tasks;this.alerts=alerts;this.users=users;this.reports=reports;this.events=events;
        this.config=config;this.gateway=gateway;this.tx=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.executor=executor;
    }

    /** Called in the same transaction that creates the report/outbox. */
    public void initialize(AlertRecord alert) {
        String username=config.accountFor(alert.getRecipient());
        if(username!=null) users.findByUsername(username).filter(u->u.isEnabled()
                && u.getRoles().stream().anyMatch(r->r.equals("ROLE_ADMIN")||r.equals("ROLE_COUNSELOR")))
                .ifPresent(u->alert.assigneeUserId=u.getId());
    }
    private record Work(ReportTask task,AlertRecord alert,PsychologicalReport report,String owner) {}
    private Work claim(Long id) {
        return tx.execute(s->{
            ReportTask hint=tasks.findById(id).orElse(null);
            if(hint==null||hint.alertId==null) return null;
            AlertRecord alert=alerts.lock(hint.alertId).orElseThrow();
            ReportTask task=tasks.lock(id).orElseThrow();
            Instant now=Instant.now();
            if(!Set.of("RUNNING","PENDING","RETRYING").contains(task.status)) return null;
            if("RUNNING".equals(task.status)) {
                if(task.leaseUntil!=null && task.leaseUntil.isAfter(now)) return null;
                if(!gateway.safeReplay(task)) {unknown(task,alert,"发送进程中断，结果待核对");return null;}
                task.status="RETRYING";
            }
            if(alert.acknowledgedAt!=null || (task.reminderRound>0 && alert.escalatedAt!=null)) {task.status="CANCELLED";task.owner=null;task.leaseUntil=null;return null;}
            if(task.nextAttempt!=null && task.nextAttempt.isAfter(now)) return null;
            if(task.attempts>=3) {unknown(task,alert,"发送重试达到上限，请人工核对");return null;}
            gateway.prepare(task,alert);
            if(task.firstSendAt!=null && MailGateway.resendProvider(task.provider) && !gateway.safeReplay(task)) {
                unknown(task,alert,"发送去重有效期已过，禁止盲目重发");return null;
            }
            if(task.firstSendAt==null) task.firstSendAt=now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            task.status="RUNNING";task.owner=UUID.randomUUID().toString();task.leaseUntil=now.plusSeconds(90);task.attempts++;
            alert.incrementAttempts();
            PsychologicalReport report=alert.getReport();
            report.getUser().getUsername();report.getUser().getDisplayName();
            if(report.getSession()!=null) report.getSession().getPublicId();
            return new Work(task,alert,report,task.owner);
        });
    }
    public void execute(Long id) {
        Work work=claim(id); if(work==null) return;
        // Recheck after queuing/claiming; an already in-flight SMTP/API call cannot be recalled.
        boolean acknowledged=tx.execute(s->alerts.findById(work.alert.getId()).orElseThrow().acknowledgedAt!=null);
        MailGateway.Receipt receipt=null; Exception failure=null;
        if(!acknowledged) try{receipt=gateway.send(work.task,work.alert,work.report);}catch(Exception ex){failure=ex;}
        final MailGateway.Receipt result=receipt; final Exception error=failure;
        tx.executeWithoutResult(s->{
            AlertRecord alert=alerts.lock(work.alert.getId()).orElseThrow();
            ReportTask task=tasks.lock(id).orElseThrow();
            if(!Objects.equals(task.owner,work.owner)||!"RUNNING".equals(task.status))return;
            task.owner=null;task.leaseUntil=null;
            if(acknowledged){task.status="CANCELLED";return;}
            if(error==null) {
                task.status="SUCCESS";task.error=null;task.providerEmailId=result.id();task.acceptedAt=Instant.now();
                task.deliveryStatus=result.delivery();
                if(result.id()!=null) task.nextReconcileAt=Instant.now().plusSeconds(60);
                if(alert.acknowledgedAt==null && alert.escalatedAt==null)
                    alert.nextReminderAt=Instant.now().plusSeconds(config.getReminderSeconds());
            } else {
                // A provider response with permanent rejection is different from an unknown send outcome.
                boolean permanent=error instanceof MailGateway.ProviderException p && p.permanent();
                boolean ambiguous=isAmbiguous(error);
                task.error=shorten(error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"发送失败"));
                if(permanent) task.status="FAILED";
                else if(gateway.safeReplay(task)) task.status=task.attempts<3?"RETRYING":"UNKNOWN";
                else task.status=ambiguous?"UNKNOWN":task.attempts<3?"RETRYING":"FAILED";
                task.nextAttempt=Instant.now().plusSeconds(10L*task.attempts);
                if(Set.of("FAILED","UNKNOWN").contains(task.status)) {
                    task.deliveryStatus="UNKNOWN";escalate(alert,"邮件发送失败或结果不确定，请人工核对");
                }
            }
            aggregate(task,alert);
        });
    }
    private boolean isAmbiguous(Throwable ex) {
        for(Throwable t=ex;t!=null;t=t.getCause()) {
            String value=t.toString().toLowerCase(Locale.ROOT);
            if(value.contains("timeout")||value.contains("timed out")||value.contains("connection reset")
                    ||value.contains("broken pipe")||value.contains("eof"))return true;
        }
        return false;
    }
    private void unknown(ReportTask task,AlertRecord alert,String reason) {
        task.status="UNKNOWN";task.deliveryStatus="UNKNOWN";task.error=reason;task.owner=null;task.leaseUntil=null;
        escalate(alert,reason);aggregate(task,alert);
    }
    private void escalate(AlertRecord alert,String reason){alert.escalatedAt=Instant.now();alert.escalationReason=reason;alert.nextReminderAt=null;}
    private void aggregate(ReportTask task,AlertRecord alert) {
        ToolStatus status="SUCCESS".equals(task.status)?ToolStatus.SUCCESS:
                Set.of("PENDING","RUNNING","RETRYING").contains(task.status)?ToolStatus.PENDING:ToolStatus.FAILED;
        alert.setStatus(status);alert.setErrorMessage(task.error);
        // Report's email status describes initial sends. Reminder delivery has its own task history.
        tasks.flush();
        var report=reports.lock(task.reportId).orElseThrow();
        var initial=tasks.findByReportId(task.reportId).stream().filter(t->"MAIL".equals(t.kind)&&t.reminderRound==0).toList();
        report.setEmailStatus(initial.stream().allMatch(t->"SUCCESS".equals(t.status))?ToolStatus.SUCCESS:
                initial.stream().anyMatch(t->Set.of("FAILED","UNKNOWN").contains(t.status))?ToolStatus.FAILED:ToolStatus.PENDING);
    }

    @Scheduled(fixedDelayString="${mindharbor.mail.poll-ms:5000}",initialDelayString="${mindharbor.tools.initial-delay-ms:3000}")
    public void poll() {
        for(var event:events.due(Instant.now(),PageRequest.of(0,100))) safe(()->applyEvent(event.id));
        for(Long id:alerts.reminderDue(Instant.now(),PageRequest.of(0,32))) safe(()->remind(id));
        for(Long id:tasks.reconcileDue(Instant.now(),PageRequest.of(0,16))) {
            try{executor.execute(()->safe(()->reconcile(id)));}catch(org.springframework.core.task.TaskRejectedException ex){break;}
        }
    }
    private void safe(Runnable action){try{action.run();}catch(Exception ex){log.warn("Mail workflow background operation failed: {}",ex.getClass().getSimpleName());}}
    public void remind(Long alertId) {
        tx.executeWithoutResult(s->{
            AlertRecord alert=alerts.lock(alertId).orElseThrow();
            if(alert.acknowledgedAt!=null||alert.escalatedAt!=null||alert.nextReminderAt==null||alert.nextReminderAt.isAfter(Instant.now()))return;
            var rounds=tasks.findByAlertIdOrderByReminderRoundAsc(alertId);
            if(rounds.isEmpty())return;
            ReportTask last=rounds.get(rounds.size()-1);
            if(!"SUCCESS".equals(last.status))return; // Never bypass a failed/unknown round by creating a new key.
            if(Set.of("BOUNCED","FAILED","COMPLAINED","SUPPRESSED").contains(Objects.toString(last.deliveryStatus,""))) {
                escalate(alert,"收件地址无法投递，请人工联系");return;
            }
            if(alert.reminderCount>=config.getMaxReminders()){escalate(alert,"提醒次数已达上限，辅导员仍未确认");return;}
            ReportTask next=new ReportTask();next.reportId=last.reportId;next.alertId=alertId;next.kind="MAIL";
            next.recipient=last.recipient;next.reminderRound=last.reminderRound+1;
            tasks.save(next);alert.reminderCount=next.reminderRound;alert.nextReminderAt=null;alert.setStatus(ToolStatus.PENDING);
        });
    }

    public void applyEvent(String eventId) {
        tx.executeWithoutResult(s->{
            MailDeliveryEvent event=events.findById(eventId).orElseThrow();if(event.applied)return;
            ReportTask hint=tasks.findFirstByProviderEmailId(event.providerEmailId).orElse(null);
            if(hint==null && event.mailKey!=null)hint=tasks.findFirstByIdempotencyKey(event.mailKey).orElse(null);
            if(hint==null||!MailGateway.resendProvider(hint.provider)||!"MAIL".equals(hint.kind)) {
                event.nextAttempt=Instant.now().plusSeconds(300);
                if(event.receivedAt.plus(Duration.ofHours(24)).isBefore(Instant.now())) {event.applied=true;event.note="No matching task after 24 hours";}
                return;
            }
            AlertRecord alert=alerts.lock(hint.alertId).orElseThrow();ReportTask task=tasks.lock(hint.id).orElseThrow();
            if(task.providerEmailId!=null && !task.providerEmailId.equals(event.providerEmailId))return;
            if(task.providerEmailId==null) {
                task.providerEmailId=event.providerEmailId;task.acceptedAt=event.occurredAt;
                task.status="SUCCESS";task.owner=null;task.leaseUntil=null;task.error=null;
                task.deliveryStatus="ACCEPTED";task.nextReconcileAt=Instant.now().plusSeconds(60);
                if(alert.acknowledgedAt==null && alert.escalatedAt==null)alert.nextReminderAt=Instant.now().plusSeconds(config.getReminderSeconds());
                aggregate(task,alert);
            }
            updateDelivery(task,alert,event.type,event.occurredAt,false);event.applied=true;
        });
    }
    public void reconcile(Long id) {
        String providerId=tx.execute(s->{
            ReportTask task=tasks.lock(id).orElseThrow();
            if(task.nextReconcileAt==null||task.nextReconcileAt.isAfter(Instant.now()))return null;
            task.nextReconcileAt=Instant.now().plusSeconds(300);task.reconcileAttempts++;
            return task.providerEmailId;
        });
        if(providerId==null)return;
        String state;
        try{String provider=tasks.findById(id).orElseThrow().provider;
            state="RESEND_MCP".equals(provider)?gateway.lookup(provider,providerId):gateway.lookup(providerId);
        }catch(Exception ex){state="lookup_failed";}
        final String observed=state;
        tx.executeWithoutResult(s->{
            ReportTask hint=tasks.findById(id).orElseThrow();AlertRecord alert=alerts.lock(hint.alertId).orElseThrow();
            ReportTask task=tasks.lock(id).orElseThrow();
            updateDelivery(task,alert,observed,Instant.now(),true);
            if(task.nextReconcileAt!=null && (task.reconcileAttempts>=24 || (task.acceptedAt!=null
                    && task.acceptedAt.plus(Duration.ofHours(24)).isBefore(Instant.now())))) {
                task.nextReconcileAt=null;
                if(!"DELIVERED".equals(task.deliveryStatus)) {task.deliveryStatus="UNKNOWN";escalate(alert,"投递结果核对超时，请人工核实");}
            }
        });
    }
    private void updateDelivery(ReportTask task,AlertRecord alert,String raw,Instant at,boolean lookup) {
        String type=raw.replace("email.","").toLowerCase(Locale.ROOT);
        String status=switch(type){case "sent","scheduled"->"ACCEPTED";case "delivered"->"DELIVERED";
            case "delivery_delayed"->"DELAYED";case "bounced"->"BOUNCED";case "failed"->"FAILED";
            case "complained"->"COMPLAINED";case "suppressed"->"SUPPRESSED";default->null;};
        if(status==null)return; // Opens/clicks do not mean delivered or human acknowledgment.
        if(!lookup && task.deliveryEventAt!=null && at.isBefore(task.deliveryEventAt))return;
        String previous=Objects.toString(task.deliveryStatus,"");
        if(Set.of("BOUNCED","FAILED","COMPLAINED","SUPPRESSED").contains(previous))return;
        if("DELIVERED".equals(previous)&&Set.of("ACCEPTED","DELAYED").contains(status))return;
        task.deliveryStatus=status;if(!lookup)task.deliveryEventAt=at;
        if("DELIVERED".equals(status)||Set.of("BOUNCED","FAILED","COMPLAINED","SUPPRESSED").contains(status))task.nextReconcileAt=null;
        if(Set.of("BOUNCED","FAILED","COMPLAINED","SUPPRESSED").contains(status)) {
            escalate(alert,"邮件投递异常："+status);
            for(ReportTask pending:tasks.findByAlertIdOrderByReminderRoundAsc(alert.getId()))
                if(Set.of("PENDING","RETRYING").contains(pending.status))pending.status="CANCELLED";
        }
    }

    public record Round(Long taskId,int round,String sendStatus,String deliveryStatus,String providerEmailId,
                        int attempts,Instant acceptedAt,String error) {}
    public record Detail(Long id,Long reportId,String recipient,Long assigneeUserId,Long acknowledgedBy,Instant acknowledgedAt,
                         int reminderCount,Instant nextReminderAt,Instant escalatedAt,String escalationReason,boolean canAcknowledge,List<Round> rounds) {}
    private boolean authorized(CurrentUser user,AlertRecord alert) {
        return user!=null && Objects.equals(alert.assigneeUserId,user.getId()) && user.getAuthorities().stream()
                .anyMatch(a->Set.of("ROLE_ADMIN","ROLE_COUNSELOR").contains(a.getAuthority()));
    }
    public Detail detail(Long id,CurrentUser user) {
        return tx.execute(s->{AlertRecord alert=alerts.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            boolean admin=user!=null&&user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
            if(!admin&&!authorized(user,alert))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            return detail(alert,user);});
    }
    private Detail detail(AlertRecord a,CurrentUser user) {
        return new Detail(a.getId(),a.getReport().getId(),a.getRecipient(),a.assigneeUserId,a.acknowledgedBy,a.acknowledgedAt,
                a.reminderCount,a.nextReminderAt,a.escalatedAt,a.escalationReason,authorized(user,a)&&a.acknowledgedAt==null,
                tasks.findByAlertIdOrderByReminderRoundAsc(a.getId()).stream().map(t->new Round(t.id,t.reminderRound,t.status,
                        t.deliveryStatus,t.providerEmailId,t.attempts,t.acceptedAt,t.error)).toList());
    }
    public Detail acknowledge(Long id,CurrentUser user) {
        return tx.execute(s->{
            AlertRecord a=alerts.lock(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            if(!authorized(user,a))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned counselor can acknowledge");
            if(a.acknowledgedAt==null){a.acknowledgedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);a.acknowledgedBy=user.getId();a.nextReminderAt=null;
                for(ReportTask t:tasks.findByAlertIdOrderByReminderRoundAsc(id))
                    if(Set.of("PENDING","RETRYING").contains(t.status))t.status="CANCELLED";
                if(a.getStatus()==ToolStatus.PENDING)a.setStatus(ToolStatus.SKIPPED);
            }
            return detail(a,user);
        });
    }
    private String shorten(String v){return v.substring(0,Math.min(500,v.length()));}
}
