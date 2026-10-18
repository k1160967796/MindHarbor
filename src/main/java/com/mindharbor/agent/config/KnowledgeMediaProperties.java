package com.mindharbor.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Optional upload-time document understanding; independent of the conversation model. */
@Component
@ConfigurationProperties(prefix = "mindharbor.knowledge.media")
public class KnowledgeMediaProperties {
    private boolean enabled;
    private String ocrBaseUrl = "http://localhost:8091";
    private String ocrToken = "";
    private String visionBaseUrl = "http://localhost:11434";
    private String visionModel = "qwen2.5vl:7b";
    private boolean visionEnabled = true;
    private int timeoutSeconds = 120;
    private int renderDpi = 200;
    private int maxPdfPages = 30;
    private long maxImagePixels = 20_000_000;
    private int maxBlockChars = 12000;
    private String storagePath = "./data/knowledge-images";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getOcrBaseUrl() { return ocrBaseUrl; }
    public void setOcrBaseUrl(String value) { ocrBaseUrl = value; }
    public String getOcrToken() { return ocrToken; }
    public void setOcrToken(String value) { ocrToken = value; }
    public String getVisionBaseUrl() { return visionBaseUrl; }
    public void setVisionBaseUrl(String value) { visionBaseUrl = value; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String value) { visionModel = value; }
    public boolean isVisionEnabled() { return visionEnabled; }
    public void setVisionEnabled(boolean value) { visionEnabled = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    public int getRenderDpi() { return renderDpi; }
    public void setRenderDpi(int value) { renderDpi = value; }
    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int value) { maxPdfPages = value; }
    public long getMaxImagePixels() { return maxImagePixels; }
    public void setMaxImagePixels(long value) { maxImagePixels = value; }
    public int getMaxBlockChars() { return maxBlockChars; }
    public void setMaxBlockChars(int value) { maxBlockChars = value; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String value) { storagePath = value; }
}
