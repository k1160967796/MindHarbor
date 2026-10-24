package com.mindharbor.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindharbor.agent.domain.*;
import java.time.*;
import java.util.*;

/** Conservative checks around model extraction. These rules do not replace risk assessment. */
public final class MemoryFactPolicy {
    private MemoryFactPolicy() {}
    private static final Set<String> STABLE=Set.of("response_length","language","tone","major","academic_stage");
    private static final Set<String> KNOWN=Set.of("response_length","language","tone","major","academic_stage",
            "sleep_status","stress_status","exam_status","work_status","relationship_status","support_need");
    private static final Set<String> SCOPES=Set.of("GLOBAL","DAILY","EXAM","FAMILY","WORK","RELATIONSHIP","OTHER");
    public record Candidate(UserMemoryType type,String key,String value,String scope,String summary,String evidence,
                            double confidence,MemoryFactStatus status,Instant effectiveAt) {}
    public static boolean stable(String key){return STABLE.contains(key);}
    public static String canonical(String key) {
        if(key==null)return "";
        key=key.substring(key.lastIndexOf(':')+1).toLowerCase(Locale.ROOT);
        return switch(key) {
            case "sleep","sleep_problem","sleep_quality","insomnia","sleep_pattern" -> "sleep_status";
            case "stress","anxiety","anxiety_status","exam_anxiety" -> "stress_status";
            case "answer_length","reply_length","verbosity" -> "response_length";
            case "preferred_language","reply_language" -> "language";
            default -> key;
        };
    }
    public static Candidate parse(JsonNode n,String input,Instant assertedAt) {
        String key=canonical(n.path("key").asText());
        String summary=n.path("summary").asText().trim(), evidence=n.path("evidence").asText().trim();
        String value=n.path("value").asText().trim(),scope=n.path("scope").asText("OTHER").toUpperCase(Locale.ROOT);
        double confidence=n.path("confidence").asDouble(-1);
        if(!key.matches("[a-z][a-z0-9_]{0,49}") || summary.length()<4 || summary.length()>80 || value.isBlank() || value.length()>60 || value.matches("(?s).*[:：;；].*")
                || evidence.isBlank() || evidence.length()>160 || !input.contains(evidence) || !Double.isFinite(confidence)
                || confidence<.55 || confidence>1 || !SCOPES.contains(scope))return null;
        if((summary+key).matches("(?s).*(诊断|风险等级|risk_level|diagnosis|\\[手机号\\]|\\[学号\\]|\\[姓名\\]|\\[证件号\\]).*"))return null;
        String clause=clause(input,evidence);
        if(!n.path("subject").asText().equals("SELF") || clause.matches("(?s).*(我朋友|我室友|我同学|他最近|她最近|他说|她说).*"))return null;
        String temporal=n.path("temporal").asText();
        if(temporal.equals("HYPOTHETICAL") || clause.matches("(?s).*(如果|假如|要是|假设|希望能|可能会).*"))return null;
        MemoryFactStatus status=temporal.equals("CURRENT")?MemoryFactStatus.ACTIVE:
                temporal.equals("HISTORICAL")?MemoryFactStatus.HISTORICAL:MemoryFactStatus.NEEDS_CONFIRMATION;
        if(clause.matches("(?s).*(上周|上个月|去年|以前|曾经|过去|当时).*" ) && !clause.matches("(?s).*(现在|目前|最近|如今|至今|仍然).*"))status=MemoryFactStatus.HISTORICAL;
        if(!KNOWN.contains(key) || scope.equals("OTHER") || confidence<.8 || clause.matches("(?s).*(也许|说不准|不确定|不记得).*"))status=MemoryFactStatus.NEEDS_CONFIRMATION;
        if(stable(key))scope="GLOBAL";
        if(scope.equals("EXAM") && !input.matches("(?s).*(考试|期末|期中|复习|考研).*"))status=MemoryFactStatus.NEEDS_CONFIRMATION;
        // Normalise common states from evidence, including negation, rather than trusting an inverted label.
        if(key.equals("response_length")) {
            if(clause.matches("(?s).*(简短|简洁|不要展开|不用详细|不需要详细|别太详细).*")) {value="concise";summary="希望回答简短";}
            else if(clause.matches("(?s).*(详细|展开|多解释).*")){value="detailed";summary="希望回答详细";}
            else status=MemoryFactStatus.NEEDS_CONFIRMATION;
            if(clause.matches("(?s).*(不要|不用|别|不想|不喜欢).{0,5}(简短|简洁).*"))status=MemoryFactStatus.NEEDS_CONFIRMATION;
        }
        if(key.equals("language")) {
            if(clause.contains("中文")&&!clause.matches("(?s).*(不要|不用|别用)中文.*")){value="zh";summary="希望使用中文回答";}
            else if(clause.contains("英文")&&!clause.matches("(?s).*(不要|不用|别用)英文.*")){value="en";summary="希望使用英文回答";}
            else status=MemoryFactStatus.NEEDS_CONFIRMATION;
            if(clause.matches("(?s).*(不|别).{0,6}(中文|英文).*"))status=MemoryFactStatus.NEEDS_CONFIRMATION;
        }
        if(key.equals("sleep_status")) {
            if(clause.matches("(?s).*(睡得.{0,3}不好|睡得.{0,3}不太好|睡得.{0,3}不怎么好|睡得.{0,3}很差).*")){value="difficulty";summary="用户自述存在睡眠困难";}
            else if(clause.matches("(?s).*(睡眠.*恢复|睡眠.*改善|睡得.*好|能睡着|不再失眠|没有失眠|不失眠).*")) {value="improved";summary="用户自述睡眠改善或没有失眠";}
            else if(clause.matches("(?s).*(睡不着|失眠|难以入睡|入睡困难|睡不好|无法入睡).*")){value="difficulty";summary="用户自述存在睡眠困难";}
            else status=MemoryFactStatus.NEEDS_CONFIRMATION;
            if(clause.matches("(?s).*(不能睡着|没有恢复|没恢复|未恢复|没有改善|没改善|并非|不是说|不是不|不是睡).*"))status=MemoryFactStatus.NEEDS_CONFIRMATION;
        }
        if(key.equals("stress_status")) {
            if(clause.matches("(?s).*(不再焦虑|不焦虑|没有压力|压力.*缓解|焦虑.*缓解).*")){value="improved";summary="用户自述压力或焦虑缓解";}
            else if(clause.matches("(?s).*(焦虑|压力|紧张).*")){value="present";summary="用户自述存在压力或焦虑";}
            else status=MemoryFactStatus.NEEDS_CONFIRMATION;
            if(clause.matches("(?s).*(没有缓解|没缓解|未缓解|并非|不是说|不是不).*"))status=MemoryFactStatus.NEEDS_CONFIRMATION;
        }
        Instant effective=null;
        String date=n.path("effectiveDate").asText("");
        if(!date.isBlank()) {
            try {
                LocalDate parsed=LocalDate.parse(date);
                LocalDate assertionDate=assertedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                if(input.contains(date) && !parsed.isAfter(assertionDate)) {
                    effective=parsed.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
                    if(status==MemoryFactStatus.ACTIVE && parsed.isBefore(assertionDate)
                            && !clause.matches("(?s).*(现在|目前|最近|至今|仍然).*"))status=MemoryFactStatus.HISTORICAL;
                }
                else status=MemoryFactStatus.NEEDS_CONFIRMATION;
            }catch(Exception invalid){status=MemoryFactStatus.NEEDS_CONFIRMATION;}
        }
        UserMemoryType type;
        try {type=UserMemoryType.valueOf(n.path("type").asText());}catch(Exception invalid){return null;}
        if(Set.of("response_length","language","tone").contains(key))type=UserMemoryType.PREFERENCE;
        if(Set.of("sleep_status","stress_status").contains(key))type=UserMemoryType.WELLBEING_PATTERN;
        return new Candidate(type,key,value,scope,summary,evidence,confidence,status,effective);
    }
    private static String clause(String input,String evidence) {
        int start=input.indexOf(evidence),end=start+evidence.length();
        while(start>0 && "，。！？；\n".indexOf(input.charAt(start-1))<0)start--;
        while(end<input.length() && "，。！？；\n".indexOf(input.charAt(end))<0)end++;
        return input.substring(start,end);
    }
    /** Narrow literal fallbacks for common explicit statements; not a replacement for arbitrary fact extraction. */
    public static List<Candidate> explicitFacts(String input,Instant assertedAt,com.fasterxml.jackson.databind.ObjectMapper json) {
        var out=new ArrayList<Candidate>();
        if(input.matches("(?s).*(假装|角色扮演|写一个|写一篇|编一个|举个例子).*"))return out;
        for(String sentence:input.split("[。！？；;\\n]")) {
            if(sentence.length()<4 || sentence.length()>80)continue;
            var keys=new ArrayList<String>();
            if(sentence.matches("(?s).*(睡不着|失眠|难以入睡|睡眠.*恢复|睡眠.*改善|睡得.*好|能睡着|睡不好).*"))keys.add("sleep_status");
            if(sentence.matches("(?s).*(我.*(压力|焦虑|紧张)|压力.*缓解|焦虑.*缓解).*"))keys.add("stress_status");
            if(sentence.matches("(?s).*(以后|希望|习惯|改成|请).*(简短|简洁|详细|展开|多解释).*"))keys.add("response_length");
            if(sentence.matches("(?s).*(以后|希望|习惯|改成|请).*(中文|英文).*"))keys.add("language");
            String scope=sentence.contains("平时")||sentence.contains("日常")?"DAILY":
                    input.matches("(?s).*(考试|期末|期中|考研|复习).*")?"EXAM":"GLOBAL";
            for(String key:keys) {
                var n=json.valueToTree(Map.of("type",stable(key)?"PREFERENCE":"WELLBEING_PATTERN","key",key,"value","stated",
                        "scope",scope,"subject","SELF","temporal","CURRENT","summary",sentence,"evidence",sentence,"confidence",.9));
                // Explicit dates are retained only when actually present; relative past remains HISTORICAL.
                var date=java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(sentence);
                if(date.find())((com.fasterxml.jackson.databind.node.ObjectNode)n).put("effectiveDate",date.group());
                var c=parse(n,input,assertedAt);if(c!=null)out.add(c);
            }
        }
        return out;
    }
    /** A conservative foreground mask while background extraction is pending; no new model call. */
    public static boolean mentioned(String key,String input) {
        if(input==null)return false;
        return switch(canonical(key)) {
            case "sleep_status" -> input.matches("(?s).*(睡|失眠|入睡).*");
            case "stress_status" -> input.matches("(?s).*(压力|焦虑|紧张).*");
            case "response_length" -> input.matches("(?s).*(简短|简洁|详细|展开|多解释).*");
            case "language" -> input.matches("(?s).*(中文|英文|英语|语言).*");
            case "exam_status" -> input.matches("(?s).*(考试|复习|期末|期中).*");
            default -> false;
        };
    }
    public static boolean relevantScope(String scope,String input) {
        Set<String> topics=new HashSet<>();
        if(input.matches("(?s).*(考试|复习|期末|期中|考研).*"))topics.add("EXAM");
        if(input.matches("(?s).*(工作|求职|实习).*"))topics.add("WORK");
        if(input.matches("(?s).*(父母|家庭|家人).*"))topics.add("FAMILY");
        if(input.matches("(?s).*(朋友|室友|恋爱|分手).*"))topics.add("RELATIONSHIP");
        return scope.equals("GLOBAL") || topics.isEmpty() || topics.contains(scope);
    }
}
