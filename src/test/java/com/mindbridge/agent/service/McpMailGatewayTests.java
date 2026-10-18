package com.mindbridge.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.*;
import com.mindbridge.agent.domain.*;
import com.mindbridge.agent.service.mcp.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpMailGatewayTests {
    @Test void remoteTaskKeepsFrozenIdentityAndRoutesDeliveryLookup()throws Exception {
        var json=new ObjectMapper();var client=mock(McpToolClient.class);
        var config=new MailWorkflowProperties();config.setProvider("mcp-resend");
        var gateway=new MailGateway(config,new MindBridgeProperties(),mock(AlertNotifier.class),json,client);
        var task=new ReportTask();task.id=2L;task.reportId=3L;task.alertId=4L;task.recipient="teacher@example.com";
        gateway.prepare(task,new AlertRecord());task.firstSendAt=Instant.now();
        assertThat(task.provider).isEqualTo("RESEND_MCP");assertThat(gateway.safeReplay(task)).isTrue();
        when(client.callJson(eq("mindbridge_send_risk_alert"),anyMap())).thenReturn(json.readTree("{\"status\":\"ACCEPTED\",\"providerEmailId\":\"remote-1\"}"));
        gateway.send(task,null,null);gateway.send(task,null,null);
        ArgumentCaptor<Map<String,Object>> args=ArgumentCaptor.forClass(Map.class);
        verify(client,times(2)).callJson(eq("mindbridge_send_risk_alert"),args.capture());
        assertThat(args.getAllValues().get(0)).isEqualTo(args.getAllValues().get(1)).containsEntry("payloadJson",task.mailPayload).containsEntry("idempotencyKey",task.idempotencyKey);
        when(client.callJson(eq("mindbridge_get_mail_status"),anyMap())).thenReturn(json.readTree("{\"status\":\"OK\",\"deliveryStatus\":\"delivered\"}"));
        assertThat(gateway.lookup(task.provider,"remote-1")).isEqualTo("delivered");
        when(client.callJson(eq("mindbridge_send_risk_alert"),anyMap())).thenReturn(json.readTree("{\"status\":\"FAILED\",\"httpCode\":422}"));
        assertThatThrownBy(()->gateway.send(task,null,null)).isInstanceOf(MailGateway.ProviderException.class);
    }
}
