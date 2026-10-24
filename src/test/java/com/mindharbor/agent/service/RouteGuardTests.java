package com.mindharbor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindharbor.agent.domain.IntentType;
import com.mindharbor.agent.service.ai.AiMessage;
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
    @ValueSource(strings={
            "一个人回宿舍的时候，我总觉得自己比别人差。最近总觉得很空，和朋友聊天也开心不起来，我需要一点支持，但不知道怎么开口。",
            "我最近开心不起来", "我这几天提不起劲", "我感觉心里空落落的", "我现在没有动力",
            "准备课堂展示时，和同学沟通时我总怕说错话，整个人很紧绷，身体也会发紧、心跳变快，我需要一点实际的应对办法。"})
    void everydaySelfDescriptionsAlsoNeedConsultReview(String input) {
        var result=guard.validate(IntentType.CHAT,input,List.of());
        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.reason()).isEqualTo("CURRENT_CONSULT_SIGNAL");
    }
    @ParameterizedTest
    @ValueSource(strings={"翻译“我最近开心不起来”", "如果我开心不起来该怎么写故事", "我朋友最近开心不起来",
            "我并没有觉得自己比别人差", "我不再觉得自己没有价值", "我需要一点支持来修复数据库", "我觉得缓存很空是什么原因"})
    void everydayDistressGuardDoesNotTreatQuotedOtherNegatedOrTechnicalTextAsSelfDistress(String input) {
        assertThat(guard.validate(IntentType.CHAT,input,List.of()).intent()).isEqualTo(IntentType.CHAT);
    }
    @Test void indirectSelfDangerCannotTakeGeneralHelpShortcut() {
        String input="刚才这种想法又出现了，我现在担心自己会做出危险的事。我觉得活着太累了，想找个办法让自己消失，我需要你帮我先稳住当前这一刻。";
        var ai=org.mockito.Mockito.mock(com.mindharbor.agent.service.ai.AiClient.class);
        assertThat(new IntentClassifier(ai).classify(input)).isEqualTo(IntentType.RISK);
        assertThat(guard.validate(IntentType.CHAT,input,List.of()).intent()).isEqualTo(IntentType.RISK);
        assertThat(guard.validate(IntentType.CHAT,"现在只是作业太多",List.of(AiMessage.user(input))).intent()).isEqualTo(IntentType.CONSULT);
        org.mockito.Mockito.verifyNoInteractions(ai);
    }
    @ParameterizedTest
    @ValueSource(strings={"翻译“我觉得活着太累了”", "我朋友想让自己消失", "小说角色想让自己消失",
            "如果我觉得活着太累了，这句话怎么翻译", "我不想让自己消失", "我不再觉得活着太累"})
    void newDangerPhrasesRespectQuotedOtherHypotheticalAndNegatedContexts(String input) {
        assertThat(com.mindharbor.agent.service.ai.RiskLexicon.hasHighRiskSignal(input)).isFalse();
    }
    @Test void genericHelpWordingMustReachModelInsteadOfBeingAssumedGeneralTask() {
        var ai=org.mockito.Mockito.mock(com.mindharbor.agent.service.ai.AiClient.class);
        org.mockito.Mockito.when(ai.complete(org.mockito.ArgumentMatchers.anyList())).thenReturn("CONSULT");
        assertThat(new IntentClassifier(ai).classify("请帮我稳住当前这一刻")).isEqualTo(IntentType.CONSULT);
        org.mockito.Mockito.verify(ai).complete(org.mockito.ArgumentMatchers.anyList());
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
