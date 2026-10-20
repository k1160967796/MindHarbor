package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Public synonym catalog derived from training inputs, never from their output at runtime. */
public final class KnowledgeTagCatalog {
    public record Entry(String id, String tag, String background, String emotion, List<String> aliases,
                        String query, boolean direct, String scope, List<String> sources, List<String> hints) {}
    public static final List<Entry> ENTRIES = load();
    private static final Map<String,List<String>> BACKGROUNDS = Map.of(
            "exam",List.of("考试","考前","期末","期中","考研","复习","成绩","排名"),
            "task",List.of("作业","论文","任务","截止","ddl"),
            "presentation",List.of("课堂展示","课堂汇报","上台展示"),
            "social",List.of("同学沟通","和同学交流","跟同学说话"),
            "career",List.of("实习","求职","面试"),
            "other",List.of("室友矛盾","父母","家庭","分手","恋爱","经济","生病"));
    private static final Pattern NEGATED = Pattern.compile(
            "(没有|并不|并非|不是|不再|不太|不怎么|没那么|毫不|不觉得|不感到|不担心|不紧张|不焦虑|不害怕|不需要|不用担心)");
    private KnowledgeTagCatalog() {}
    public static Entry byTag(String tag) { return ENTRIES.stream().filter(e->e.tag().equals(tag)).findFirst().orElse(null); }
    private static List<Entry> load() {
        try (var input=KnowledgeTagCatalog.class.getResourceAsStream("/knowledge-tags/catalog.json")) {
            var entries=List.of(new ObjectMapper().readValue(input,Entry[].class));
            if(entries.stream().map(Entry::id).distinct().count()!=entries.size()) throw new IllegalStateException("Duplicate catalog IDs");
            return entries;
        } catch(Exception e) { throw new IllegalStateException("Invalid knowledge tag catalog",e); }
    }
    public static boolean unsafeOrComplex(String text) {
        return NEGATED.matcher(text).find() || has(text,
                "自残","自杀","伤害自己","结束生命","不想活","活着太累","让自己消失","撑不住","控制不住","很危险",
                "几周","几个月","长期","越来越","加重","持续","没用","无效","试过","上次","之前","刚才",
                "这个问题","这种情况","药","胸痛","胸口疼","呼吸困难","晕厥","霸凌","威胁","暴力",
                "我朋友","我同学","他","她","假如","假设","如果","早醒","噩梦","恐慌","崩溃");
    }
    public static boolean unambiguous(String text) {
        long backgrounds=BACKGROUNDS.values().stream().filter(words->words.stream().anyMatch(text::contains)).count();
        if(backgrounds>1) return false;
        Set<String> symptoms=new HashSet<>();
        for(var entry:ENTRIES) {
            if(entry.id().equals("career-worry") && !has(text,"实习","求职","面试")) continue;
            if(entry.aliases().stream().anyMatch(text::contains)) symptoms.add(entry.tag().split("\\|")[2]);
        }
        return symptoms.size()<=1;
    }
    /** Supplemental tags only; the three original tags retain their tested v1 rules. */
    public static Entry expandedMatch(String text) {
        if(text.length()>180 || unsafeOrComplex(text)) return null;
        Set<String> backgrounds=new HashSet<>();
        BACKGROUNDS.forEach((id,words)->{if(words.stream().anyMatch(text::contains)) backgrounds.add(id);});
        if(backgrounds.size()>1 || backgrounds.contains("other")) return null;
        boolean low=has(text,"低落","很空","空落落","开心不起来","提不起兴趣","动力也没有","没动力",
                "什么都不想做","被落下了","觉得自己很差","觉得自己比别人差","没有意义");
        if(low && has(text,"焦虑","紧张")) return null;
        if(!low && !has(text,"焦虑","紧张","担心","害怕","怕","压力很大","着急","紧绷")) return null;
        String background=backgrounds.stream().findFirst().orElse(
                has(text,"压力","学习")?"stress":"daily");
        List<Entry> matches=ENTRIES.stream()
                .filter(e->!Set.of("exam-sleep","exam-focus","task-start").contains(e.id()))
                .filter(e->e.emotion().equals(low?"低落":"焦虑"))
                .filter(e->e.background().equals(background) || low && e.background().equals("daily"))
                .filter(e->e.aliases().stream().anyMatch(text::contains)).toList();
        if(matches.size()!=1) return null;
        Entry found=matches.get(0);
        // Multiple manifestations are not collapsed into one broad package.
        Set<String> manifestations=new HashSet<>();
        for(Entry e:ENTRIES) if(e.aliases().stream().anyMatch(text::contains)) {
            String symptom=e.tag().split("\\|")[2];
            if(e.id().equals("career-worry") && !background.equals("career")) continue;
            if(e.id().equals("social-tension") && !background.equals("social")) continue;
            manifestations.add(symptom);
        }
        if(manifestations.size()>1) return null;
        return found;
    }
    private static boolean has(String text,String...words) { return Arrays.stream(words).anyMatch(text::contains); }
}
