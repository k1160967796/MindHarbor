package com.mindbridge.agent.service.knowledge;

/** One layout region, before chunking. Image references are opaque local filenames. */
public record KnowledgeBlock(Integer pageNumber, String type, String text, String imageRef, boolean needsReview) {
    public boolean isStructured() {
        return !"text".equals(type);
    }
}
