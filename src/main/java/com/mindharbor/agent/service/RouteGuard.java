package com.mindharbor.agent.service;

import com.mindharbor.agent.domain.IntentType;
import com.mindharbor.agent.service.ai.AiMessage;
import com.mindharbor.agent.service.ai.RiskLexicon;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 在 CHAT 路由提交前进行本地规则复核，不调用模型，也不替代 RiskGuardian 的风险评估。
 * 只提升路由，不降低分类器已经判定的 CONSULT/RISK。
 */
@Service
public class RouteGuard {

    private static final int RECENT_USER_LIMIT = 3;
    private static final List<String> DISTRESS = List.of(
            "难受", "撑不住", "坚持不下去", "快受不了了", "没有人理解我", "什么都不想做");
    private static final List<String> TOPIC_SWITCH = List.of(
            "换个话题", "换一个话题", "说点别的", "聊点别的", "先不聊这个", "先不说这个",
            "change the topic", "something else");
    private static final List<String> GENERAL_REQUEST = List.of(
            "解释", "定义", "什么意思", "是什么", "翻译", "写论文", "写一篇", "写个故事",
            "举例", "科普", "推荐", "区别", "代码", "编程", "数据库", "接口", "压力测试",
            "explain", "definition", "translate", "stress test");
    private static final Pattern PERSONAL_DISTRESS = Pattern.compile(
            "(?:我|自己)(?:最近|这几天|这段时间|现在|一直|总是|又|也|真的|感觉|觉得|很|特别|非常|太|有点|越来越|好|很大|的压力)*"
                    + "(?:焦虑|难受|失眠|睡不着|害怕|恐慌|孤独|低落|抑郁|崩溃|心慌|痛苦|想哭|撑不住|压力很大|压力太大)"
                    + "|\\b(?:i am|i'm|i feel|i have been)\\s+(?:(?:so|very|really)\\s+)?(?:anxious|depressed|sad|lonely|stressed)\\b");
    private static final Pattern QUOTED_TEXT = Pattern.compile("“[^”]*”|「[^」]*」|\"[^\"]*\"");
    private static final Pattern EVERYDAY_PERSONAL_DISTRESS = Pattern.compile(
            "(?:我|自己)[^，。！？；\\n]{0,24}(?:开心不起来|高兴不起来|提不起劲|提不起精神|提不起兴趣|没有动力|没动力|比别人差|没有价值|很空|空落落|怕说错话|怕做不好|很紧绷|心跳变快)");
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(?:(?:那|那么|所以|可是|但是|还是|现在|然后)[，, ]*)?(?:我)?(?:该|应该|要)?(?:怎么办|怎么做|怎么解决|如何解决|如何缓解|如何改善)(?:这个问题|这种情况)?[呢啊吗？?。!！ ]*$"
                    + "|^(?:还是这样|还是不行|还是没用|比昨天更严重了|比之前更严重了|你刚才说的方法没用|试过了没用|然后呢)[呢啊？?。!！ ]*$"
                    + "|^(?:what should i do|it is getting worse|that didn't help)[?.! ]*$");

    public Result validate(IntentType candidate, String currentInput, List<AiMessage> previousHistory) {
        if (candidate != IntentType.CHAT) {
            return new Result(candidate, "KEEP_NON_CHAT");
        }
        String input = normalize(currentInput);
        // 与已有分类器的高风险词规则保持一致；此处也是防御性兜底。
        if (RiskLexicon.hasHighRiskSignal(input)) {
            return new Result(IntentType.RISK, "CURRENT_HIGH_RISK_SIGNAL");
        }
        // 近期明确危险信号需要继续评估；解释压力来源或换话题不等于风险解除。
        // 提升为 CONSULT 以执行 RiskGuardian，不在路由层永久锁定 HIGH。
        if (RiskLexicon.hasRecentHighRiskUserSignal(previousHistory)) {
            return new Result(IntentType.CONSULT, "RECENT_HIGH_RISK_REVIEW");
        }
        if (hasConsultEvidence(input)) {
            return new Result(IntentType.CONSULT, "CURRENT_CONSULT_SIGNAL");
        }
        if (!containsAny(input, TOPIC_SWITCH) && isFollowUp(input)
                && hasRecentConsultContext(previousHistory)) {
            return new Result(IntentType.CONSULT, "RECENT_CONSULT_FOLLOW_UP");
        }
        return new Result(IntentType.CHAT, "NO_CONSULT_EVIDENCE");
    }

    private boolean hasConsultEvidence(String input) {
        String unquoted = QUOTED_TEXT.matcher(input).replaceAll("");
        // Everyday descriptions of distress may have no clinical/lexicon word such as 抑郁.
        // Evaluate clauses so a quote, a hypothetical or somebody else's state is not self evidence.
        for(String clause:unquoted.split("[，。！？；\\n]")) {
            if(EVERYDAY_PERSONAL_DISTRESS.matcher(clause).find()
                    && !containsAny(clause,GENERAL_REQUEST)
                    && !clause.matches("(?s).*(如果|假如|假设|要是|我朋友|我室友|我同学|他说|她说|并没有|不是|不再|没有觉得|没有感觉|不觉得|不感觉).*"))
                return true;
        }
        if (!RiskLexicon.hasConsultSignal(input) && !containsAny(input, DISTRESS)) {
            return false;
        }
        // 普通知识问答本身不是个人困扰；同一句中明确表达自身困扰仍应复核。
        return PERSONAL_DISTRESS.matcher(unquoted).find() || !containsAny(input, GENERAL_REQUEST);
    }

    private boolean hasRecentConsultContext(List<AiMessage> history) {
        if (history == null) {
            return false;
        }
        int usersSeen = 0;
        for (int i = history.size() - 1; i >= 0 && usersSeen < RECENT_USER_LIMIT; i--) {
            AiMessage message = history.get(i);
            if (!"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            usersSeen++;
            String input = normalize(message.content());
            if (hasConsultEvidence(input) || RiskLexicon.hasHighRiskSignal(input)) {
                return true;
            }
            // 不跨越已经结束/切换的话题，把更早的咨询强行带入当前问题。
            if (containsAny(input, TOPIC_SWITCH) || !isFollowUp(input)) {
                return false;
            }
        }
        return false;
    }

    private boolean isFollowUp(String input) {
        return FOLLOW_UP.matcher(input).find();
    }

    private boolean containsAny(String input, List<String> signals) {
        return signals.stream().anyMatch(input::contains);
    }

    private String normalize(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    public record Result(IntentType intent, String reason) {}
}
