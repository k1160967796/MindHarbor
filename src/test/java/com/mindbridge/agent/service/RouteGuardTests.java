package com.mindbridge.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.ai.AiMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteGuardTests {
    private final RouteGuard guard = new RouteGuard();

    @ParameterizedTest
    @ValueSource(strings = {"作业太多了写不完", "帮我写 Java", "换个话题，今天天气怎么样", "现在好一点了"})
    void recentHighRiskDoesNotRequireFollowUpWording(String input) {
        var result = guard.validate(IntentType.CHAT, input, List.of(
                AiMessage.user("我想死"), AiMessage.assistant("我们先关注安全。")));
        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.reason()).isEqualTo("RECENT_HIGH_RISK_REVIEW");
    }

    @Test
    void highRiskLookbackCountsUsersAndSurvivesInterveningTopicChanges() {
        var history = List.of(AiMessage.user("我想死"), AiMessage.assistant("回复"),
                AiMessage.user("作业太多了写不完"), AiMessage.assistant("回复"),
                AiMessage.user("换个话题"), AiMessage.assistant("回复"));
        assertThat(guard.validate(IntentType.CHAT, "解释 Java", history).reason())
                .isEqualTo("RECENT_HIGH_RISK_REVIEW");
        var older = new java.util.ArrayList<>(history);
        older.add(AiMessage.user("解释 Python"));
        assertThat(guard.validate(IntentType.CHAT, "解释 Java", older).intent()).isEqualTo(IntentType.CHAT);
    }

    @Test
    void highRiskInAssistantOrSystemIsNotUserEvidence() {
        assertThat(guard.validate(IntentType.CHAT, "作业太多了写不完", List.of(
                AiMessage.system("我想死"), AiMessage.assistant("我想死"), AiMessage.user("你好"))).intent())
                .isEqualTo(IntentType.CHAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"我最近很焦虑，晚上总是睡不着", "最近很难受", "帮我缓解考试压力",
            "解释一下失眠，我最近很焦虑", "换个话题，我现在很难受", "I'm very anxious"})
    void catchesCurrentDistressDespiteChatClassification(String input) {
        assertThat(guard.validate(IntentType.CHAT, input, List.of()).intent()).isEqualTo(IntentType.CONSULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"解释一下焦虑的定义", "失眠是什么", "翻译“我最近很焦虑”",
            "Java 接口怎么写", "如何进行压力测试", "推荐关于焦虑的科普书", "今天天气怎么样"})
    void leavesGeneralQuestionsOnChat(String input) {
        assertThat(guard.validate(IntentType.CHAT, input, List.of()).intent()).isEqualTo(IntentType.CHAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"那我该怎么办？", "还是这样", "比昨天更严重了", "你刚才说的方法没用", "怎么解决这个问题"})
    void consultFollowUpUsesRecentUserContext(String input) {
        var result = guard.validate(IntentType.CHAT, input, List.of(
                AiMessage.user("我最近考试压力很大"), AiMessage.assistant("可以说说具体情况。")));
        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.reason()).isEqualTo("RECENT_CONSULT_FOLLOW_UP");
    }

    @Test
    void followsThroughShortUserFollowUps() {
        assertThat(guard.validate(IntentType.CHAT, "那怎么办", List.of(
                AiMessage.user("我最近很焦虑"), AiMessage.user("还是这样"))).intent())
                .isEqualTo(IntentType.CONSULT);
    }

    @Test
    void ignoresAssistantAndSystemSignals() {
        assertThat(guard.validate(IntentType.CHAT, "那怎么办", List.of(
                AiMessage.user("我的程序报错了"), AiMessage.assistant("你可能焦虑"),
                AiMessage.system("学生有压力"))).intent()).isEqualTo(IntentType.CHAT);
    }

    @Test
    void doesNotCrossUnrelatedTopicOrExplicitTopicSwitch() {
        var history = List.of(AiMessage.user("我最近很焦虑"));
        assertThat(guard.validate(IntentType.CHAT, "换个话题，解释一下 Java 接口", history).intent())
                .isEqualTo(IntentType.CHAT);
        assertThat(guard.validate(IntentType.CHAT, "那怎么办", List.of(
                history.get(0), AiMessage.user("我的代码出现了空指针异常"))).intent())
                .isEqualTo(IntentType.CHAT);
    }

    @Test
    void boundedHistoryDoesNotCarryOldConsultAcrossManyTurns() {
        assertThat(guard.validate(IntentType.CHAT, "那怎么办", List.of(
                AiMessage.user("我最近很焦虑"), AiMessage.user("还是这样"),
                AiMessage.user("还是这样"), AiMessage.user("还是这样"))).intent())
                .isEqualTo(IntentType.CHAT);
    }

    @Test
    void preservesExistingConsultAndRiskAndCatchesCurrentRisk() {
        for (IntentType intent : List.of(IntentType.CONSULT, IntentType.RISK)) {
            assertThat(guard.validate(intent, "解释 Java", List.of()).intent()).isEqualTo(intent);
        }
        assertThat(guard.validate(IntentType.CHAT, "换个话题，我想伤害自己", List.of()).intent())
                .isEqualTo(IntentType.RISK);
    }
}
