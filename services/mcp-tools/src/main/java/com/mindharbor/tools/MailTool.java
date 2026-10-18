package com.mindharbor.tools;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MailTool {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MailTool.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String key,base,from;
    private final Set<String> recipients;
    private final ReentrantLock[] locks=new ReentrantLock[64];
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public MailTool(JdbcTemplate db,ObjectMapper json,@Value("${tools.resend-key}") String key,
            @Value("${tools.resend-base}") String base,@Value("${tools.allowed-from}") String from,
            @Value("${tools.allowed-recipients}") String allowed) {
        this.db=db;this.json=json;this.key=key;this.base=base.replaceAll("/+$","");this.from=from;
        recipients=new HashSet<>();for(String r:allowed.split(","))if(!r.isBlank())recipients.add(r.trim().toLowerCase(Locale.ROOT));
        for(int i=0;i<locks.length;i++)locks[i]=new ReentrantLock();
        db.execute("CREATE TABLE IF NOT EXISTS mail_calls (task_key VARCHAR(80) PRIMARY KEY,task_id BIGINT UNIQUE NOT NULL,request_json CLOB NOT NULL,payload CLOB NOT NULL,first_at BIGINT NOT NULL,status VARCHAR(24) NOT NULL,provider_id VARCHAR(160),http_code INT NOT NULL DEFAULT 0)");
    }
    public Map<String,Object> send(Long taskId,Long reportId,Long alertId,int round,String taskKey,String firstAt,String payload) {
        ReentrantLock lock=locks[Math.floorMod(Objects.hashCode(taskKey),locks.length)];
        if(!lock.tryLock())return outcome("RETRYABLE",null,429);
        try {
            long first;
            try {
                if(taskId==null||taskId<=0||reportId==null||reportId<=0||alertId==null||alertId<=0||round<0
                        ||taskKey==null||!taskKey.matches("mindharbor-mail-[a-f0-9-]{36}"))throw new IllegalArgumentException();
                first=Instant.parse(firstAt).toEpochMilli();
                if(first>System.currentTimeMillis()+300000)throw new IllegalArgumentException();
                validate(payload,taskKey);
            }catch(Exception ex){return outcome("FAILED",null,422);}
            String identity;
            try{identity=json.writeValueAsString(List.of(taskId,reportId,alertId,round,taskKey,first,payload));}
            catch(Exception ex){return outcome("FAILED",null,422);}
            var rows=db.queryForList("SELECT * FROM mail_calls WHERE task_key=?",taskKey);
            if(rows.isEmpty()) {
                try {db.update("INSERT INTO mail_calls(task_key,task_id,request_json,payload,first_at,status) VALUES(?,?,?,?,?,?)",taskKey,taskId,identity,payload,first,"CREATED");}
                catch(org.springframework.dao.DuplicateKeyException ex){return outcome("FAILED",null,422);}
            }else{
                String original=db.queryForObject("SELECT request_json FROM mail_calls WHERE task_key=?",String.class,taskKey);
                if(!identity.equals(original))return outcome("FAILED",null,422);
                Map<String,Object> previous=result(taskKey);
                if(Set.of("ACCEPTED","FAILED").contains(previous.get("status")))return previous;
                first=((Number)rows.get(0).get("FIRST_AT")).longValue();
            }
            if(System.currentTimeMillis()-first>=Duration.ofHours(23).toMillis()) {
                db.update("UPDATE mail_calls SET status='UNKNOWN' WHERE task_key=?",taskKey);
                return outcome("UNKNOWN",null,0);
            }
            if(key.isBlank())return outcome("RETRYABLE",null,503);
            db.update("UPDATE mail_calls SET status='RUNNING' WHERE task_key=?",taskKey);
            try {
                HttpResponse<String> response=request("POST","/emails",payload,taskKey);
                int code=response.statusCode();
                if(code/100!=2){
                    boolean permanent=code>=400&&code<500&&!Set.of(408,409,429).contains(code);
                    String state=permanent?"FAILED":"RETRYABLE";
                    db.update("UPDATE mail_calls SET status=?,http_code=? WHERE task_key=?",state,code,taskKey);
                    return outcome(state,null,code);
                }
                String id=json.readTree(response.body()).path("id").asText();
                if(id.isBlank()||id.length()>160)throw new java.io.IOException("Missing provider ID");
                db.update("UPDATE mail_calls SET status='ACCEPTED',provider_id=?,http_code=? WHERE task_key=?",id,code,taskKey);
                log.info("MCP mail accepted taskId={} reportId={} round={} providerEmailId={}",taskId,reportId,round,id);
                return outcome("ACCEPTED",id,code);
            }catch(Exception ex){
                // The provider may have accepted the email. Only replay the original key inside its window.
                db.update("UPDATE mail_calls SET status='UNKNOWN' WHERE task_key=?",taskKey);
                return outcome("RETRYABLE",null,0);
            }
        }finally{lock.unlock();}
    }
    private void validate(String payload,String taskKey)throws Exception {
        if(payload==null||payload.length()>64000)throw new IllegalArgumentException();
        JsonNode body=json.readTree(payload);
        Set<String> fields=Set.of("from","to","subject","text","tags");
        body.fieldNames().forEachRemaining(name->{if(!fields.contains(name))throw new IllegalArgumentException();});
        if(!body.path("from").asText().equals(from)||body.path("subject").asText().isBlank()||body.path("text").asText().isBlank())throw new IllegalArgumentException();
        JsonNode to=body.path("to");if(!to.isArray()||to.size()!=1||!recipients.contains(to.get(0).asText().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
        JsonNode tags=body.path("tags");
        if(!tags.isArray()||tags.size()!=1||!"mail_key".equals(tags.get(0).path("name").asText())||!taskKey.equals(tags.get(0).path("value").asText()))throw new IllegalArgumentException();
    }
    public Map<String,Object> result(String taskKey) {
        var rows=db.queryForList("SELECT status,provider_id,http_code FROM mail_calls WHERE task_key=?",taskKey);
        if(rows.isEmpty())return outcome("NOT_FOUND",null,404);
        var row=rows.get(0);return outcome((String)row.get("STATUS"),(String)row.get("PROVIDER_ID"),((Number)row.get("HTTP_CODE")).intValue());
    }
    public Map<String,Object> delivery(String id) {
        if(id==null||!id.matches("[a-zA-Z0-9_-]{1,160}"))return outcome("FAILED",null,422);
        if(db.queryForObject("SELECT COUNT(*) FROM mail_calls WHERE provider_id=?",Integer.class,id)==0)return outcome("NOT_FOUND",null,404);
        try {
            HttpResponse<String> response=request("GET","/emails/"+id,null,null);
            if(response.statusCode()/100!=2)return outcome("RETRYABLE",id,response.statusCode());
            return Map.of("status","OK","providerEmailId",id,"deliveryStatus",json.readTree(response.body()).path("last_event").asText("unknown"));
        }catch(Exception ex){return outcome("RETRYABLE",id,0);}
    }
    private HttpResponse<String> request(String method,String path,String payload,String taskKey)throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+key);
        if(method.equals("POST"))builder.header("Content-Type","application/json").header("Idempotency-Key",taskKey).POST(HttpRequest.BodyPublishers.ofString(payload));
        else builder.GET();
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,Object> outcome(String state,String id,int code) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("status",state);result.put("providerEmailId",id==null?"":id);result.put("httpCode",code);return result;
    }
}
