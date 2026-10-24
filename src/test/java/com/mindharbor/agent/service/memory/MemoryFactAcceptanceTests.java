package com.mindharbor.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.service.ai.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:memory-facts;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "mindharbor.memory.background-enabled=false","mindharbor.memory.background-idle-ms=0","mindharbor.agent.recovery-enabled=false",
    "mindharbor.knowledge.use-chroma=false","mindharbor.memory.use-chroma=false","mindharbor.tools.initial-delay-ms=3600000",
    "spring.ai.mcp.server.enabled=false","spring.ai.mcp.client.enabled=false"})
@AutoConfigureWebTestClient
class MemoryFactAcceptanceTests {
    @Autowired MemoryMaintenanceService jobs;
    @Autowired UserProfileMemoryService profiles;
    @Autowired UserMemoryItemRepository memories;
    @Autowired UserAccountRepository users;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired WebTestClient web;
    @MockBean AiClient ai;
    @MockBean UserMemoryChromaGateway chroma;
    Long user,sid;long order;String forcedModelOutput;
    @BeforeEach void setup(){
        user=users.findByUsername("student").orElseThrow().getId();order=0;forcedModelOutput=null;
        tx.executeWithoutResult(s->{
            em.createQuery("delete from MemoryTask").executeUpdate();em.createQuery("delete from UserMemoryChange").executeUpdate();
            em.createQuery("delete from UserMemoryItem").executeUpdate();em.createQuery("delete from MemoryUserState").executeUpdate();
            em.createQuery("delete from MemoryFactDeletion").executeUpdate();
            var gate=em.find(MemoryWorkerGate.class,1L);gate.owner=null;gate.leaseUntil=null;gate.lastForegroundAt=Instant.EPOCH;
            var session=new ChatSession();session.setUser(em.getReference(UserAccount.class,user));session.setPublicId(UUID.randomUUID().toString());session.setTitle("memory fixture");em.persist(session);sid=session.getId();
        });
        when(chroma.query(anyLong(),anyString(),anyInt())).thenReturn(List.of());
    }
    Map<String,Object> candidate(String key,String value,String scope,String text){
        return new LinkedHashMap<>(Map.of("type",key.equals("response_length")||key.equals("language")?"PREFERENCE":"WELLBEING_PATTERN",
                "key",key,"value",value,"scope",scope,"subject","SELF","temporal","CURRENT","effectiveDate","","summary",text,"evidence",text,"confidence",.95));
    }
    MemoryTask submit(String input,long source,Instant asserted,List<Map<String,Object>> candidates) throws Exception {
        String output=forcedModelOutput==null?json.writeValueAsString(candidates):forcedModelOutput;
        when(ai.completeWithin(anyList(),any(),anyMap(),anyInt())).thenReturn(output);
        var task=tx.execute(s->{
            var m=new ChatMessage();m.setUser(em.getReference(UserAccount.class,user));m.setSession(em.getReference(ChatSession.class,sid));
            m.setRole(MessageRole.USER);m.setContent(input);m.setCreatedAt(asserted);em.persist(m);em.flush();
            var t=new MemoryTask();t.kind="PROFILE";t.taskKey="fixture:"+UUID.randomUUID();t.userId=user;t.sessionId=sid;t.sourceOrder=source;
            t.sourceMessageId=m.getId();t.assertedAt=asserted;t.requestId=UUID.randomUUID().toString();t.input=input;em.persist(t);return t;
        });
        for(int i=0;i<12;i++)jobs.runNext();
        var result=jobs.recentTasks().stream().filter(t->t.id.equals(task.id)).findFirst().orElseThrow();
        assertThat(result.status).isIn("SUCCESS","SKIPPED");return result;
    }
    void send(String input,Map<String,Object> c) throws Exception {submit(input,++order,Instant.now(),List.of(c));}
    List<UserMemoryItem> all(){return memories.findByUser_IdOrderByUpdatedAtDesc(user);}
    String brief(String input){return profiles.profileBrief(users.findById(user).orElseThrow(),input);}
    @Test void preferenceChangesReuseRowAndKeepHistoryButConfirmationsDoNotDuplicate() throws Exception {
        String a="以后请详细回答我的问题";send(a,candidate("response_length","detailed","GLOBAL",a));long id=all().get(0).getId();
        String b="以后请简短回答我的问题";send(b,candidate("answer_length","concise","GLOBAL",b));
        assertThat(all()).singleElement().satisfies(m->{assertThat(m.getId()).isEqualTo(id);assertThat(m.getFactValue()).isEqualTo("concise");assertThat(m.getSourceMessageId()).isNotNull();});
        send(b,candidate("response_length","concise","GLOBAL",b));
        assertThat(profiles.changes(user,id)).hasSize(2);assertThat(all().get(0).getRevision()).isEqualTo(3);
        assertThat(profiles.changes(user,id).get(0).priorStatus).isEqualTo("SUPERSEDED");
    }
    @Test void sleepAliasRecoveryChangesValueAndRetainsPriorEvidence() throws Exception {
        String a="最近考试期间我一直睡不着";send(a,candidate("insomnia","difficulty","EXAM",a));long id=all().get(0).getId();
        String b="考试已经结束，最近睡眠恢复了";send(b,candidate("sleep_quality","improved","EXAM",b));
        assertThat(all()).singleElement().satisfies(m->{assertThat(m.getFactValue()).isEqualTo("improved");assertThat(m.getFactKey()).isEqualTo("sleep_status");});
        assertThat(profiles.changes(user,id).get(0).beforeJson).contains("睡不着");
    }
    @Test void contextsCoexistWithoutOverwritingEachOther() throws Exception {
        String a="考试之前我总是睡不着";send(a,candidate("sleep_status","difficulty","EXAM",a));
        String b="平时我睡得很好";send(b,candidate("sleep_status","improved","DAILY",b));
        assertThat(all()).hasSize(2);assertThat(all()).extracting(UserMemoryItem::getFactScope).containsExactlyInAnyOrder("EXAM","DAILY");
        assertThat(brief("工作怎样安排")).doesNotContain("考试","睡眠");
    }
    @Test void historicalInputDoesNotReplaceCurrentEvenWhenModelLabelsItCurrent() throws Exception {
        String a="最近我睡得很好";send(a,candidate("sleep_status","improved","GLOBAL",a));long id=all().get(0).getId();
        String b="上个月我一直睡不着";send(b,candidate("sleep_status","difficulty","GLOBAL",b));
        assertThat(all().get(0).getFactValue()).isEqualTo("improved");
        assertThat(profiles.changes(user,id).get(0).reason).isEqualTo("HISTORICAL_OBSERVATION");
    }
    @Test void hypotheticalAndOtherPersonAreNotPromotedToFacts() throws Exception {
        for(String text:List.of("如果考试没过我可能会睡不着","我朋友最近晚上睡不着"))send(text,candidate("sleep_status","difficulty","GLOBAL",text));
        assertThat(all()).isEmpty();
    }
    @Test void negationCorrectsInvertedModelValue() throws Exception {
        String text="最近我已经不再失眠了";send(text,candidate("sleep_status","difficulty","GLOBAL",text));
        assertThat(all()).singleElement().satisfies(m->assertThat(m.getFactValue()).isEqualTo("improved"));
    }
    @Test void uncertainUpdateDoesNotInheritOldHighConfidence() throws Exception {
        String a="希望回答我的问题时语气温和";send(a,candidate("tone","gentle","GLOBAL",a));long id=all().get(0).getId();
        String b="希望回答我的问题时语气直接";var low=candidate("tone","direct","GLOBAL",b);low.put("confidence",.65);send(b,low);
        assertThat(all().get(0).getFactValue()).isEqualTo("gentle");assertThat(profiles.changes(user,id).get(0).reason).isEqualTo("REVIEW_REQUIRED");
        var accepted=candidate("tone","direct","GLOBAL",b);accepted.put("confidence",.82);send(b,accepted);
        assertThat(all().get(0).getConfidence()).isEqualTo(.82);
    }
    @Test void oldTaskCannotRevertSameFactButCanAddUnrelatedFact() throws Exception {
        String a="以后请简短回答我的问题";submit(a,20,Instant.now(),List.of(candidate("response_length","concise","GLOBAL",a)));
        String b="以前请详细回答我的问题，以后请使用中文回答";
        submit(b,10,Instant.now().minusSeconds(60),List.of(candidate("response_length","detailed","GLOBAL","以前请详细回答我的问题"),candidate("language","zh","GLOBAL","以后请使用中文回答")));
        assertThat(all()).hasSize(2);assertThat(all().stream().filter(m->m.getFactKey().equals("response_length")).findFirst().orElseThrow().getFactValue()).isEqualTo("concise");
    }
    @Test void expirationAppliesToSqlFallbackAndChromaMatches() throws Exception {
        String text="最近晚上总是睡不着";submit(text,1,Instant.now().minus(Duration.ofDays(20)),List.of(candidate("sleep_status","difficulty","GLOBAL",text)));
        var m=all().get(0);assertThat(m.statusAt(Instant.now())).isEqualTo(MemoryFactStatus.EXPIRED);
        when(chroma.query(anyLong(),anyString(),anyInt())).thenReturn(List.of(new UserMemoryChromaGateway.UserMemoryMatch(m.getId(),.99,m.getSourceOrder())));
        assertThat(brief("请给我建议")).isEqualTo("无已保存用户画像。");
    }
    @Test void staleIndexFilteredAndValidSqlCandidatesFillTheGap() throws Exception {
        String text="以后请使用中文回答";send(text,candidate("language","zh","GLOBAL",text));var m=all().get(0);
        when(chroma.query(anyLong(),anyString(),anyInt())).thenReturn(List.of(new UserMemoryChromaGateway.UserMemoryMatch(m.getId(),.99,0)));
        assertThat(brief("请给我建议")).contains("中文");
        verify(chroma).query(eq(user),anyString(),eq(24));
    }
    @Test void currentInputMasksOldFactBeforeBackgroundUpdate() throws Exception {
        String text="最近晚上总是睡不着";send(text,candidate("sleep_status","difficulty","GLOBAL",text));
        assertThat(brief("最近睡眠已经恢复了")).doesNotContain("睡眠困难");
        assertThat(brief("你还记得我的情况吗")).contains("睡眠困难","自述时间");
    }
    @Test void historyEndpointChecksOwnerAndDeletionRemovesHistory() throws Exception {
        String text="以后请使用中文回答";send(text,candidate("language","zh","GLOBAL",text));long id=all().get(0).getId();
        web.get().uri("/api/profile/memory/"+id+"/changes").headers(h->h.setBasicAuth("admin","admin123")).exchange().expectStatus().isNotFound();
        web.get().uri("/api/profile/memory/"+id+"/changes").headers(h->h.setBasicAuth("student","student123")).exchange().expectStatus().isOk();
        profiles.deleteMemory(user,id);
        Long remaining=tx.execute(s->em.createQuery("select count(c) from UserMemoryChange c",Long.class).getSingleResult());
        assertThat(remaining).isEqualTo(0L);
    }
    @Test void legacyRowsAreVisibleButNotAssumedCurrent() {
        tx.executeWithoutResult(s->{var m=new UserMemoryItem();m.setUser(em.getReference(UserAccount.class,user));m.setMemoryKey("sleep_problem");m.setSummary("旧的考试睡眠问题");em.persist(m);});
        assertThat(profiles.memoriesForUser(user)).hasSize(1);
        assertThat(brief("给我一些建议")).isEqualTo("无已保存用户画像。");
    }
    @Test void deletedFactIsNotRecreatedByDelayedTask() throws Exception {
        String text="以后请使用中文回答";send(text,candidate("language","zh","GLOBAL",text));long id=all().get(0).getId();
        Instant old=Instant.now().minusSeconds(5);profiles.deleteMemory(user,id);
        submit(text,10,old,List.of(candidate("language","zh","GLOBAL",text)));
        assertThat(all()).isEmpty();
    }
    @Test void absoluteFactTimeDoesNotAllowNewMessageToOverwriteLaterFact() throws Exception {
        String a="2026-10-20至今我睡得很好";var newer=candidate("sleep_status","improved","GLOBAL",a);newer.put("effectiveDate","2026-10-20");
        submit(a,1,Instant.parse("2026-10-23T00:00:00Z"),List.of(newer));
        String b="2026-10-01我一直睡不着";var older=candidate("sleep_status","difficulty","GLOBAL",b);older.put("effectiveDate","2026-10-01");
        submit(b,2,Instant.parse("2026-10-23T00:00:00Z"),List.of(older));
        assertThat(all().get(0).getFactValue()).isEqualTo("improved");
    }
    @Test void literalSleepStatementSurvivesMalformedModelAttribute() throws Exception {
        String text="最近考试时我总是睡不着";
        var wrong=candidate("stress_status","sleep: difficulty; tone: gentle","GLOBAL",text);
        send(text,wrong);
        assertThat(all()).singleElement().satisfies(m->{assertThat(m.getFactKey()).isEqualTo("sleep_status");assertThat(m.getFactScope()).isEqualTo("EXAM");assertThat(m.getFactValue()).isEqualTo("difficulty");});
    }
    @Test void conflictingCandidatesDoNotPickArbitraryFirstCurrentValue() throws Exception {
        String text="我的语气偏好还在调整";
        submit(text,1,Instant.now(),List.of(candidate("tone","gentle","GLOBAL",text),candidate("tone","direct","GLOBAL",text)));
        assertThat(all()).singleElement().satisfies(m->assertThat(m.getFactStatus()).isEqualTo(MemoryFactStatus.NEEDS_CONFIRMATION));
    }
    @Test void invalidModelJsonUsesValidatedRulesWithVisibleOutcome() throws Exception {
        forcedModelOutput="{ broken JSON";
        String text="考试结束了，最近睡眠恢复了";
        var task=submit(text,1,Instant.now(),List.of());
        assertThat(task.resultNote).startsWith("RULE_FALLBACK:");
        assertThat(all()).singleElement().satisfies(m->{assertThat(m.getFactValue()).isEqualTo("improved");assertThat(m.getFactStatus()).isEqualTo(MemoryFactStatus.ACTIVE);});
    }
    @Test void profileSchemaHasStableGenerationOrderAndNoUnsafeStringPattern() {
        var item=(Map<?,?>)MemoryMaintenanceService.PROFILE_SCHEMA.get("items");
        var properties=(Map<?,?>)item.get("properties");
        assertThat(new ArrayList<>(properties.keySet())).isEqualTo(item.get("required"));
        assertThat(((Map<?,?>)properties.get("value")).containsKey("pattern")).isFalse();
    }
}
