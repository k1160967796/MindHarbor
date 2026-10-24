package com.mindharbor.agent.service.ai;

import java.util.List;
import java.util.Locale;

/**
 * 风险和咨询关键词词库。
 *
 * <p>它是模型判断前的硬规则兜底，主要用于快速识别明确危险信号。</p>
 */
public final class RiskLexicon {

    public static final int RECENT_HIGH_RISK_USER_LIMIT = 3;

    /** 同一会话的原始历史，不含本轮输入；不以换话题或助手回复解除近期风险。 */
    public static boolean hasRecentHighRiskUserSignal(List<AiMessage> previousHistory) {
        if (previousHistory == null) return false;
        int usersSeen = 0;
        for (int i = previousHistory.size() - 1; i >= 0 && usersSeen < RECENT_HIGH_RISK_USER_LIMIT; i--) {
            AiMessage message = previousHistory.get(i);
            if (!"user".equalsIgnoreCase(message.role())) continue;
            usersSeen++;
            if (message.content() != null && hasHighRiskSignal(message.content().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static final List<String> HIGH_RISK_WORDS = List.of(
            "不想活", "活不下去", "撑不下去", "自杀", "自残", "轻生", "结束生命", "结束这一切",
            "伤害自己", "伤人", "杀了", "想死", "去死", "没有活着的意义", "不想存在", "消失算了",
            "suicide", "kill myself", "self harm", "end my life", "hurt myself", "hurt others", "want to die"
    );

    private static final List<String> CONSULT_WORDS = List.of(
            "焦虑", "压力", "压抑", "抑郁", "低落", "失眠", "睡不着", "崩溃", "难过", "孤独",
            "情绪", "心理", "心理咨询", "咨询师", "心累", "烦躁", "紧张", "心慌", "恐慌", "睡眠",
            "害怕", "恐惧", "内耗", "想哭",
            "不开心", "没动力", "痛苦", "沮丧", "绝望", "无助", "喘不过气", "panic attack",
            "anxiety", "anxious", "stress", "depress", "sad", "insomnia", "panic", "lonely", "breakup"
    );

    private RiskLexicon() {
    }

    public static boolean hasHighRiskSignal(String text) {
        if(text==null)return false;
        if(containsAny(text, HIGH_RISK_WORDS))return true;
        String unquoted=text.replaceAll("“[^”]*”|「[^」]*」|\"[^\"]*\"", "");
        for(String clause:unquoted.split("[，。！？；\\n]")) {
            if(clause.matches("(?s).*(如果|假如|假设|我朋友|我同学|我室友|角色|小说|剧情|魔术|并没有|不再|不想让|不担心|没有想|不会).*"))continue;
            if(clause.matches("(?s).*(?:我|自己)[^，。！？；\\n]{0,16}(?:活着太累|活着没意思|活着没有意思).*" )
                    || clause.matches("(?s).*(?:想|打算|准备)[^，。！？；\\n]{0,12}(?:让自己消失|让自己彻底消失|永远消失).*" )
                    || clause.matches("(?s).*我[^，。！？；\\n]{0,6}担心自己[^，。！？；\\n]{0,8}做出危险的事.*"))return true;
        }
        return false;
    }

    public static boolean hasConsultSignal(String text) {
        return containsAny(text, CONSULT_WORDS);
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
