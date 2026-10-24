package com.mindharbor.agent.service.memory;

import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.PrivacySanitizer;
import com.mindharbor.agent.service.ai.AiMessage;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads completed background summaries. Never invokes a model on the request path. */
@Service
public class ConversationSummaryService {
    private final EntityManager em;private final TransactionTemplate tx;private final PrivacySanitizer sanitizer;
    public ConversationSummaryService(EntityManager em,TransactionTemplate tx,PrivacySanitizer sanitizer) {this.em=em;this.tx=tx;this.sanitizer=sanitizer;}
    public record Recall(String brief,List<AiMessage> history,String source) {}
    public Recall read(Long sessionId,String input,List<AiMessage> original) {
        try {
            return tx.execute(s -> {
                var summary=sessionId==null?null:em.find(ConversationMemorySummary.class,sessionId);
                if(summary==null) return fallback(original);
                if(conflictingTopic(input,summary.content)) return new Recall(recentBrief(original),bounded(original),"TOPIC_BYPASS");
                var rows=em.createQuery("select m from ChatMessage m where m.session.id=:s and (m.replyStatus is null or m.replyStatus=com.mindharbor.agent.domain.ReplyStatus.SUCCESS) order by m.createdAt desc,m.id desc",ChatMessage.class)
                        .setParameter("s",sessionId).setMaxResults(20).getResultList();
                Collections.reverse(rows);
                var tail=rows.stream().filter(m -> m.getCreatedAt().isAfter(summary.coveredAt)
                        || m.getCreatedAt().equals(summary.coveredAt) && m.getId()>summary.coveredMessageId)
                        .map(m -> new AiMessage(m.getRole().name().toLowerCase(Locale.ROOT),sanitizer.sanitize(m.getContent()))).toList();
                return new Recall("历史摘要（可能滞后，近期原文优先）：\n"+summary.content+"\n"+recentBrief(tail),bounded(tail),"SUMMARY_REUSED");
            });
        } catch(Exception ignored) {return fallback(original);}
    }
    public static Recall fallback(List<AiMessage> history) {return new Recall(recentBrief(history),bounded(history),"RECENT_RAW");}
    private static String recentBrief(List<AiMessage> history) {
        var users=history.stream().filter(m -> m.role().equals("user")).toList();
        if(users.isEmpty()) return "无相关历史记忆。";
        return "近期用户原文摘录（非摘要）：\n"+String.join("\n",users.stream().skip(Math.max(0,users.size()-2))
                .map(m -> m.content().substring(0,Math.min(240,m.content().length()))).toList());
    }
    private static List<AiMessage> bounded(List<AiMessage> history) {
        var keep=new ArrayList<AiMessage>();int chars=0;
        for(int i=history.size()-1;i>=0;i--) {var m=history.get(i);if(chars+m.content().length()>12000) break;keep.add(m);chars+=m.content().length();}
        Collections.reverse(keep);return keep;
    }
    private static Set<String> topics(String text) {
        var out=new HashSet<String>();
        for(String group:List.of("考试|复习|作业","室友|朋友|恋爱|分手","工作|求职|实习","家庭|父母|家人"))
            if(Arrays.stream(group.split("\\|")).anyMatch(text::contains))out.add(group);
        return out;
    }
    private static boolean conflictingTopic(String input,String summary) {
        var current=topics(input);var old=topics(summary);return !current.isEmpty() && !old.isEmpty() && Collections.disjoint(current,old);
    }
}
