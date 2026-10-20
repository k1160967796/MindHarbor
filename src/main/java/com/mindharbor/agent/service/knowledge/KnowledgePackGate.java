package com.mindharbor.agent.service.knowledge;

import com.mindharbor.agent.service.agent.AgentContext;
import com.mindharbor.agent.service.ai.AiMessage;
import java.util.regex.Pattern;

/**
 * Deliberately narrow grammar for a no-LLM fast path. Unknown words or history
 * fall back to RAG; this is not a general Chinese negation/parser model.
 */
public final class KnowledgePackGate {
    private KnowledgePackGate() {}
    private static final Pattern SIMPLE = Pattern.compile(
            "(?:我|最近|现在|今天|明天|今晚|这次|快|临近|马上|要|了|很|太|有点|特别|因为|所以|导致|让|感觉|觉得|"
            + "考试|考前|期末|期中|考研|作业|论文|任务|截止|ddl|"
            + "焦虑|紧张|担心|着急|"
            + "睡不着|入睡困难|难以入睡|晚上|夜里|书|"
            + "注意力不集中|注意力难集中|无法集中|难以集中|看不进去|学不进去|"
            + "拖延|不敢开始|迟迟不开始|迟迟没有开始|迟迟没开始|越|"
            + "怎么办|怎么做|怎么缓解|如何缓解|为什么|是什么原因|怎么回事|"
            + "请问|帮帮我|可以|吗|呢|啊|的|得|地|到|临|前|在|就|总|"
            + "[，。！？、,.!?：:；;\\s])+");

    public static String rejection(AgentContext context, KnowledgeTagCache cache, KnowledgeTagCache.Plan plan) {
        if (plan == null || !plan.eligible()) return "LABEL_GATE";
        var definition = KnowledgeTagCatalog.byTag(plan.tag());
        if (definition == null || !definition.direct()) return "REVIEW_REQUIRED";
        if (!KnowledgeTagCatalog.unambiguous(context.modelInput())) return "CONFLICTING_SIGNALS";
        if (!simple(context.modelInput())) return "COMPLEX_EXPRESSION";
        // Previous personal context may change applicability. Do not share a pack
        // on the strength of a complete current sentence while ignoring that context.
        for (AiMessage message : context.previousHistory()) {
            if (!"user".equals(message.role())) continue;
            if (!simple(message.content())) return "COMPLEX_HISTORY";
            var past = new AgentContext(context.user(), context.session(), message.content(), message.content());
            past.setIntent(context.intent());
            if (!plan.tag().equals(cache.plan(past).tag())) return "DIFFERENT_HISTORY";
        }
        return null;
    }

    private static boolean simple(String text) {
        if (text == null || text.length() > 180) return false;
        String normalized=text.toLowerCase(java.util.Locale.ROOT);
        if(SIMPLE.matcher(normalized).matches()) return true;
        if(KnowledgeTagCatalog.unsafeOrComplex(normalized)) return false;
        // Bounded token segmentation instead of nested regex repetition.
        // Unknown suffixes must not cause exponential regex backtracking.
        boolean[] reachable=new boolean[normalized.length()+1];
        reachable[0]=true;
        for(int i=0;i<normalized.length();i++) if(reachable[i]) {
            if(Character.isWhitespace(normalized.charAt(i)) || "，。！？、,.!?：:；;".indexOf(normalized.charAt(i))>=0)
                reachable[i+1]=true;
            for(String token:EXPANDED_TOKENS) if(normalized.startsWith(token,i)) reachable[i+token.length()]=true;
        }
        return reachable[normalized.length()];
    }

    // Neutral connective expressions observed in the training partition.
    // Full-string matching keeps unknown clauses out of the no-review path.
    private static final String[] CONTEXT_PHRASES = {
            "这几天","独自待在宿舍的时候","晚上躺下以后","收到老师通知之后","和同学比较进度时",
            "复习效率不高时","临近考试这几天","准备课堂展示时","想到实习申请的时候",
            "我总担心会把事情搞砸","我总担心自己复习不完","很害怕自己讲不好",
            "我害怕自己达不到要求","因为最近学习压力很大","最近压力很大",
            "尤其是晚上一个人的时候更明显","每次想到成绩和排名就紧张",
            "希望你帮我把压力拆开看看","想请你给我一个能马上做的小步骤",
            "我想先把呼吸和睡眠稳下来","我不知道该怎么缓解这种紧张",
            "我需要一点实际的应对办法","我担心继续这样会影响学习",
            "希望有人帮我判断现在该怎么办","想知道怎么让自己先冷静一点",
            "一想到要做","很","我","越想越焦虑","越想越难放松下来","最近",
            "晚上也","睡前一直想明天的事情","也会","这几天","学习压力","压力很大",
            "课堂展示","课堂汇报","上台展示","和同学沟通时","和同学交流","跟同学说话",
            "实习申请","实习","找不到","我就","总","担心","害怕","怕","自己","最近考试快到了",
            "时","讲不好","学习压力很大"
    };
    private static final java.util.List<String> EXPANDED_TOKENS = expandedTokens();
    private static java.util.List<String> expandedTokens() {
        var tokens=new java.util.ArrayList<String>();
        for(var e:KnowledgeTagCatalog.ENTRIES) if(e.direct()) tokens.addAll(e.aliases());
        tokens.addAll(java.util.List.of(CONTEXT_PHRASES));
        tokens.addAll(java.util.List.of(SIMPLE.pattern().substring(3,SIMPLE.pattern().lastIndexOf('|')).split("\\|")));
        return tokens.stream().distinct().toList();
    }
}
