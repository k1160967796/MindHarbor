package com.mindharbor.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="mindharbor.mail")
public class MailWorkflowProperties {
    private String provider="legacy", apiBase="https://api.resend.com", apiKey="", webhookSecret="";
    private String publicUrl="http://localhost:8080", recipientAccounts="";
    private long reminderSeconds=600;
    private int maxReminders=2;
    public String getProvider(){return provider;} public void setProvider(String v){provider=v;}
    public String getApiBase(){return apiBase;} public void setApiBase(String v){apiBase=v;}
    public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;}
    public String getWebhookSecret(){return webhookSecret;} public void setWebhookSecret(String v){webhookSecret=v;}
    public String getPublicUrl(){return publicUrl;} public void setPublicUrl(String v){publicUrl=v;}
    public String getRecipientAccounts(){return recipientAccounts;} public void setRecipientAccounts(String v){recipientAccounts=v;}
    public long getReminderSeconds(){return reminderSeconds;} public void setReminderSeconds(long v){reminderSeconds=Math.max(1,v);}
    public int getMaxReminders(){return maxReminders;} public void setMaxReminders(int v){maxReminders=Math.max(0,v);}
    public String accountFor(String email) {
        for(String entry:recipientAccounts.split(",")) {
            String[] pair=entry.trim().split("=",2);
            if(pair.length==2 && pair[0].trim().equalsIgnoreCase(email)) return pair[1].trim();
        }
        return null;
    }
}
