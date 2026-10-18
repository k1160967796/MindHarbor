package com.mindharbor.agent.service.knowledge;

import java.util.List;

public record KnowledgeCandidates(List<SearchResult> results, List<String> degradationReasons) {}
