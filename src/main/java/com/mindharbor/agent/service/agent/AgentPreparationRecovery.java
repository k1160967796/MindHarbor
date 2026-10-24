package com.mindharbor.agent.service.agent;

import com.mindharbor.agent.service.ChatService;
import com.mindharbor.agent.service.ReplyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AgentPreparationRecovery {
    private final AgentCheckpointStore checkpoints;private final ChatService chat;private final ReplyStore replies;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"agent-preparation-recovery");t.setDaemon(true);return t;});
    @Value("${mindharbor.agent.recovery-enabled:true}") private boolean enabled=true;
    public AgentPreparationRecovery(AgentCheckpointStore checkpoints,ChatService chat,ReplyStore replies){this.checkpoints=checkpoints;this.chat=chat;this.replies=replies;}
    @Scheduled(fixedDelayString="${mindharbor.agent.recovery-poll-ms:5000}",initialDelayString="${mindharbor.agent.recovery-initial-delay-ms:10000}")
    public void poll(){
        if(!enabled || !busy.compareAndSet(false,true))return;
        worker.submit(()->{try {recoverOnce();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Preparation recovery scan failed: {}",e.getClass().getSimpleName());}finally{busy.set(false);}});
    }
    public void recoverOnce(){replies.recoverExpired();for(String request:checkpoints.recoverableRequests())chat.recoverPreparation(request);}
    @jakarta.annotation.PreDestroy void close(){worker.shutdownNow();}
}
