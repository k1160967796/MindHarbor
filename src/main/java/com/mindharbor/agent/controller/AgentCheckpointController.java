package com.mindharbor.agent.controller;

import com.mindharbor.agent.service.agent.AgentCheckpointStore;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController @RequestMapping("/api/admin/agent-checkpoints")
public class AgentCheckpointController {
    private final AgentCheckpointStore checkpoints;
    public AgentCheckpointController(AgentCheckpointStore checkpoints){this.checkpoints=checkpoints;}
    @GetMapping public Mono<List<AgentCheckpointStore.Progress>> list(){
        return Mono.fromCallable(checkpoints::latest).subscribeOn(Schedulers.boundedElastic());
    }
}
