package com.mindharbor.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Called inside the fenced PROFILE transaction; model output cannot select a row belonging to another user. */
@Service
public class MemoryFactStore {
    private final EntityManager em;
    private final ObjectMapper json;
    @Value("${mindharbor.memory.state-valid-days:14}") private int stateDays=14;
    @Value("${mindharbor.memory.context-valid-days:90}") private int contextDays=90;
    public MemoryFactStore(EntityManager em,ObjectMapper json){this.em=em;this.json=json;}
    public record FactState(String key,String value,String scope,String summary,String evidence,double confidence,
            MemoryFactStatus status,Long sourceMessageId,Instant assertedAt,Instant effectiveAt,Instant expiresAt,long revision) {}
    private FactState state(UserMemoryItem m){return new FactState(m.getFactKey(),m.getFactValue(),m.getFactScope(),m.getSummary(),m.getEvidence(),m.getConfidence(),
            m.getFactStatus(),m.getSourceMessageId(),m.getAssertedAt(),m.getEffectiveAt(),m.getExpiresAt(),m.getRevision());}
    @Transactional(propagation=Propagation.MANDATORY)
    public List<UserMemoryItem> apply(MemoryTask task,List<MemoryFactPolicy.Candidate> candidates) {
        List<UserMemoryItem> items=em.createQuery("select m from UserMemoryItem m where m.user.id=:u",UserMemoryItem.class)
                .setParameter("u",task.userId).getResultList();
        var changed=new LinkedHashSet<UserMemoryItem>();
        Instant asserted=task.assertedAt==null?task.createdAt:task.assertedAt;
        var groups=new LinkedHashMap<String,List<MemoryFactPolicy.Candidate>>();
        candidates.forEach(c->groups.computeIfAbsent(c.key()+":"+c.scope(),k->new ArrayList<>()).add(c));
        for(var group:groups.values()) {
            var current=group.stream().filter(c->c.status()==MemoryFactStatus.ACTIVE).toList();
            var c=current.isEmpty()?group.get(0):current.get(0);
            if(current.stream().map(MemoryFactPolicy.Candidate::value).distinct().count()>1)
                c=new MemoryFactPolicy.Candidate(c.type(),c.key(),c.value(),c.scope(),c.summary(),c.evidence(),c.confidence(),MemoryFactStatus.NEEDS_CONFIRMATION,c.effectiveAt());
            final var chosen=c;
            var deletions=em.createQuery("select d from MemoryFactDeletion d where d.userId=:u and d.factKey=:k and d.factScope=:s",MemoryFactDeletion.class)
                    .setParameter("u",task.userId).setParameter("k",c.key()).setParameter("s",c.scope()).getResultList();
            if(deletions.stream().anyMatch(d->!asserted.isAfter(d.deletedAt)))continue;
            var item=items.stream().filter(m->chosen.key().equals(m.getFactKey())&&chosen.scope().equals(m.getFactScope())
                    || m.getFactKey()==null && MemoryFactPolicy.stable(chosen.key()) && chosen.key().equals(MemoryFactPolicy.canonical(m.getMemoryKey())))
                    .findFirst().orElse(null);
            boolean fresh=item==null;
            if(!fresh && task.sourceOrder<=item.getSourceOrder())continue;
            if(fresh){item=new UserMemoryItem();item.setUser(em.getReference(UserAccount.class,task.userId));items.add(item);}
            FactState before=fresh?null:state(item);
            Instant expiry=MemoryFactPolicy.stable(c.key())?null:asserted.plus(Duration.ofDays(
                    c.type()==UserMemoryType.WELLBEING_PATTERN?Math.max(1,stateDays):Math.max(1,contextDays)));
            MemoryFactStatus status=c.status();
            // A known earlier effective date cannot overwrite a later explicit fact even if submitted today.
            if(!fresh && c.effectiveAt()!=null && item.getEffectiveAt()!=null && c.effectiveAt().isBefore(item.getEffectiveAt()))status=MemoryFactStatus.HISTORICAL;
            if(status==MemoryFactStatus.ACTIVE && expiry!=null && !expiry.isAfter(Instant.now()))status=MemoryFactStatus.EXPIRED;
            FactState proposed=new FactState(c.key(),c.value(),c.scope(),c.summary(),c.evidence(),c.confidence(),status,
                    task.sourceMessageId,asserted,c.effectiveAt(),expiry,item.getRevision()+1);
            if(!fresh && item.getFactStatus()==MemoryFactStatus.ACTIVE && status!=MemoryFactStatus.ACTIVE) {
                // Preserve the current fact. Store uncertain/historical observations separately, without promoting them.
                record(task,item,before,proposed,status==MemoryFactStatus.HISTORICAL?"HISTORICAL_OBSERVATION":"REVIEW_REQUIRED",false);
                continue;
            }
            boolean same=!fresh && Objects.equals(item.getFactValue(),c.value()) && item.getFactStatus()==status;
            item.setFactKey(c.key());item.setMemoryKey(c.key());item.setFactValue(c.value());item.setFactScope(c.scope());item.setFactStatus(status);
            item.setType(c.type());item.setSummary(c.summary());item.setSourceOrder(task.sourceOrder);
            item.setSourceMessageId(task.sourceMessageId);item.setAssertedAt(asserted);item.setEffectiveAt(c.effectiveAt());item.setExpiresAt(expiry);
            item.setRevision(item.getRevision()+1);item.refreshSeen(em.getReference(ChatSession.class,task.sessionId),c.evidence(),c.confidence());
            if(fresh)em.persist(item);em.flush();
            if(!same)record(task,item,before,state(item),fresh?"CREATED":"REPLACED",true);
            changed.add(item);
        }
        // Bound active recall candidates without deleting stable preferences or their history.
        var active=items.stream().filter(m->m.currentAt(Instant.now())).sorted(Comparator.comparing(UserMemoryItem::getUpdatedAt).reversed()).toList();
        for(var m:active.stream().filter(m->!MemoryFactPolicy.stable(m.getFactKey())).skip(Math.max(0,40-active.stream().filter(x->MemoryFactPolicy.stable(x.getFactKey())).count())).toList()) {
            var before=state(m);m.setFactStatus(MemoryFactStatus.ARCHIVED);m.setRevision(m.getRevision()+1);
            record(task,m,before,state(m),"CAPACITY_ARCHIVE",true);changed.add(m);
        }
        return List.copyOf(changed);
    }
    private void record(MemoryTask task,UserMemoryItem item,FactState before,FactState after,String reason,boolean applied) {
        if(!em.createQuery("select c.id from UserMemoryChange c where c.memoryId=:m and c.sourceOrder=:s and c.reason=:r",Long.class)
                .setParameter("m",item.getId()).setParameter("s",task.sourceOrder).setParameter("r",reason).getResultList().isEmpty())return;
        var event=new UserMemoryChange();event.userId=task.userId;event.memoryId=item.getId();event.sourceMessageId=task.sourceMessageId;
        event.sourceOrder=task.sourceOrder;event.reason=reason;event.fromRevision=before==null?0:before.revision();event.toRevision=applied?item.getRevision():event.fromRevision;
        event.priorStatus=before==null?null:(reason.equals("REPLACED")?"SUPERSEDED":before.status().name());
        event.resultingStatus=after.status().name();
        try{event.beforeJson=before==null?null:json.writeValueAsString(before);event.afterJson=json.writeValueAsString(after);}catch(Exception e){throw new IllegalArgumentException("Fact history serialization failed",e);}
        em.persist(event);
    }
}
