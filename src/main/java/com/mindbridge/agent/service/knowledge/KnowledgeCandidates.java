package com.mindbridge.agent.service.knowledge;

import java.util.List;

public record KnowledgeCandidates(List<SearchResult> results, List<String> degradationReasons) {}
