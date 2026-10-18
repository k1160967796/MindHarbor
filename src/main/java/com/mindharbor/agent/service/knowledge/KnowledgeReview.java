package com.mindharbor.agent.service.knowledge;

import java.util.List;

public record KnowledgeReview(List<SearchResult> results, Coverage coverage, String retryQuery, String reason) {
    public enum Coverage { SUFFICIENT, PARTIAL, NONE, UNKNOWN }
}
