package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import com.mindharbor.agent.service.agent.AgentContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpandedKnowledgeTagTests {
    final KnowledgeTagCache cache=new KnowledgeTagCache(mock(StringRedisTemplate.class),
            mock(KnowledgeChunkRepository.class),new ObjectMapper(),true,1800);
    AgentContext context(String text) {
        var c=new AgentContext(new UserAccount(),new ChatSession(),text,text);
        c.setIntent(IntentType.CONSULT);return c;
    }
    @ParameterizedTest
    @CsvSource(delimiter=';',value={
            "期末让我很紧张，睡得不安稳怎么办;exam-restless",
            "最近学习压力很大，睡前想东想西怎么办;stress-rumination",
            "我课堂汇报时害怕讲不好，身体紧绷怎么办;presentation-tension",
            "和同学交流我担心说错话，整个人很紧绷怎么办;social-tension",
            "我担心找不到实习，越想越焦虑怎么办;career-worry"
    })
    void unseenParaphrasesMapToCorrectDirectEligibleGroup(String text,String id) {
        var c=context(text);var plan=cache.plan(c);
        assertThat(plan.eligible()).isTrue();
        assertThat(KnowledgeTagCatalog.byTag(plan.tag()).id()).isEqualTo(id);
        assertThat(KnowledgePackGate.rejection(c,cache,plan)).isNull();
    }
    @ParameterizedTest
    @CsvSource(delimiter=';',value={
            "作业检查个不停，我很害怕出错;task-checking",
            "我最近很低落，什么都不想做;mood-motivation",
            "最近总觉得很空，和朋友聊天也开心不起来;mood-disconnect",
            "我觉得自己很差，心里很低落;mood-selfworth"
    })
    void sensitiveThemesHaveRetrievalTagsButNeverSkipReview(String text,String id) {
        var c=context(text);var plan=cache.plan(c);
        assertThat(plan.eligible()).isTrue();
        assertThat(KnowledgeTagCatalog.byTag(plan.tag()).id()).isEqualTo(id);
        assertThat(KnowledgePackGate.rejection(c,cache,plan)).isEqualTo("REVIEW_REQUIRED");
    }
    @ParameterizedTest
    @ValueSource(strings={
            "我并不担心找不到实习，越想越焦虑",
            "我朋友很担心实习找不到，越想越焦虑",
            "如果课堂展示很害怕，心跳很快怎么办",
            "不是课堂展示，而是生病心跳很快，我很害怕",
            "我担心找不到实习，论文也写不完",
            "课堂展示紧张心跳很快，还睡不踏实",
            "考试焦虑睡不着，课堂展示也紧张",
            "我担心找不到实习，想找个办法让自己消失",
            "我担心找不到实习，越来越难受",
            "我担心找不到实习，上次的方法没用",
            "课堂展示紧张心跳很快，胸口疼",
            "我担心找不到实习，帮我写代码",
            "实习申请焦虑，不是担心结果，是没有钱吃饭",
            "我没有睡不踏实，只是考试紧张",
            "我担心找不到实习但并不焦虑"
    })
    void negativeAndAdversarialInputsNeverDirect(String text) {
        var c=context(text);
        assertThat(KnowledgePackGate.rejection(c,cache,cache.plan(c))).isNotNull();
    }
    @Test void catalogHasUniqueTagsAndEverySourceExists() {
        assertThat(KnowledgeTagCatalog.ENTRIES).hasSize(12);
        assertThat(KnowledgeTagCatalog.ENTRIES.stream().map(KnowledgeTagCatalog.Entry::tag).distinct().count()).isEqualTo(12);
        for(var e:KnowledgeTagCatalog.ENTRIES) for(String source:e.sources())
            assertThat(getClass().getResource("/knowledge/"+source)).as(e.id()+" source "+source).isNotNull();
    }
}
