package com.mindharbor.agent.domain;
import jakarta.persistence.*;

@Entity @Table(name="memory_user_state")
public class MemoryUserState {
    @Id public Long userId;
    public long lastProfileOrder;
}
