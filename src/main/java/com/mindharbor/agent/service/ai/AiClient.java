package com.mindharbor.agent.service.ai;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 模型调用统一接口。
 *
 * <p>业务层通过 complete 做分类/评估，通过 stream 做学生端流式回答。</p>
 */
public interface AiClient {

    String complete(List<AiMessage> messages);

    /** 有总时间限制的内部任务；默认收集流式结果，超时取消订阅。 */
    default String completeWithin(List<AiMessage> messages, java.time.Duration timeout,
                                  java.util.Map<String, Object> schema, int maxTokens) {
        return stream(messages).reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString).block(timeout);
    }

    Flux<String> stream(List<AiMessage> messages);

    /** Final answer budget; adapters must signal explicit provider truncation. */
    default Flux<String> streamAnswer(List<AiMessage> messages, int maxTokens) { return stream(messages); }
}
