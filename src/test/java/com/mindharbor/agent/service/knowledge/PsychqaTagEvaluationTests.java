package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.*;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import com.mindharbor.agent.service.agent.AgentContext;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline rule evaluation: no models, network, student records, or mail. */
@EnabledIfEnvironmentVariable(named="PSYCHQA_EVAL", matches="true")
class PsychqaTagEvaluationTests {
    @Test void evaluateFrozenRulesOnSelectedPartition() throws Exception {
        String partition=System.getenv().getOrDefault("PSYCHQA_PART","train");
        assertThat(partition).isIn("train","test");
        Path input=Path.of("data/lora/psychqa_"+partition+".jsonl");
        ObjectMapper json=new ObjectMapper();
        var cache=new KnowledgeTagCache(mock(StringRedisTemplate.class),
                mock(KnowledgeChunkRepository.class),json,true,1800);
        Map<String,Map<String,Integer>> counts=new TreeMap<>();
        Map<String,Integer> byTag=new TreeMap<>(),reasons=new TreeMap<>();
        List<Map<String,Object>> cases=new ArrayList<>();
        List<String> lines=Files.readAllLines(input);
        JsonNode manifest=json.readTree(Files.readString(Path.of("data/lora/psychqa_split_manifest.json")));
        String datasetHash=sha(Files.readAllBytes(input));
        assertThat(datasetHash).isEqualTo(manifest.path("outputSha256").path(input.getFileName().toString()).asText());
        for(int index=0;index<lines.size();index++){
            JsonNode row=json.readTree(lines.get(index));
            String text=row.path("input").asText();
            String gold=row.path("output").asText();
            var context=new AgentContext(new UserAccount(),new ChatSession(),text,text);
            // Deliberately force CONSULT for every input, including high-risk rows:
            // the cache must defend itself even without an upstream route decision.
            context.setIntent(IntentType.CONSULT);
            var plan=cache.plan(context);
            String rejection=KnowledgePackGate.rejection(context,cache,plan);
            boolean direct=rejection==null;
            Map<String,Integer> label=counts.computeIfAbsent(gold,k->new TreeMap<>());
            label.merge("total",1,Integer::sum);
            if(plan.eligible()) {label.merge("candidateEligible",1,Integer::sum);byTag.merge(plan.tag(),1,Integer::sum);}
            if(direct) label.merge("directEligible",1,Integer::sum);
            reasons.merge(direct?"ELIGIBLE":rejection+":"+plan.reason(),1,Integer::sum);
            cases.add(Map.of("line",index+1,"inputSha256",sha(text.getBytes(StandardCharsets.UTF_8)),
                    "emotionLabel",gold,"tag",plan.tag(),"candidateEligible",plan.eligible(),
                    "directEligible",direct,"reason",direct?"ELIGIBLE":rejection));
        }
        Files.createDirectories(Path.of("target"));
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("partition",partition);report.put("datasetSha256",datasetHash);
        report.put("catalogSha256",sha(Files.readAllBytes(Path.of("src/main/resources/knowledge-tags/catalog.json"))));
        report.put("gateSha256",sha(Files.readAllBytes(Path.of("src/main/java/com/mindharbor/agent/service/knowledge/KnowledgePackGate.java"))));
        report.put("classifierSha256",sha(Files.readAllBytes(Path.of("src/main/java/com/mindharbor/agent/service/knowledge/KnowledgeTagCatalog.java"))));
        report.put("cacheRuleSha256",sha(Files.readAllBytes(Path.of("src/main/java/com/mindharbor/agent/service/knowledge/KnowledgeTagCache.java"))));
        if(partition.equals("test")) {
            JsonNode trained=json.readTree(Files.readString(Path.of("target/psychqa-tags-train-report.json")));
            for(String field:List.of("catalogSha256","gateSha256","classifierSha256","cacheRuleSha256"))
                assertThat(report.get(field)).as("Rules must be frozen before held-out evaluation: "+field)
                        .isEqualTo(trained.path(field).asText());
        }
        report.put("total",lines.size());report.put("byEmotion",counts);report.put("byTag",byTag);
        report.put("reasons",reasons);report.put("cases",cases);
        report.put("limitations",List.of("Emotion labels are not gold retrieval/package labels",
                "Direct eligible means rules passed, not a production-approved cache hit",
                "Synthetic phrase templates may overlap across train/test",
                "No clinical answer-quality or full-chat latency evaluation"));
        Files.writeString(Path.of("target/psychqa-tags-"+partition+"-report.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(lines).hasSize(partition.equals("train")?2160:240);
        assertThat(counts.get("高风险").getOrDefault("candidateEligible",0)).isZero();
        for(String label:List.of("正常","低落","高风险"))
            assertThat(counts.get(label).getOrDefault("directEligible",0)).as(label+" cannot skip review").isZero();
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
