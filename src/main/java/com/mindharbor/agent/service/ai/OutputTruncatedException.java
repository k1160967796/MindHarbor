package com.mindharbor.agent.service.ai;

/** Provider explicitly ended generation because of its output limit. */
public class OutputTruncatedException extends RuntimeException {
    public OutputTruncatedException() { super("Model output reached its length limit"); }
}
