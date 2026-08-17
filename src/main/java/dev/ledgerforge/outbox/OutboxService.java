package dev.ledgerforge.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
  private final JdbcClient jdbc; private final ObjectMapper json;
  public OutboxService(JdbcClient jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
  public UUID append(UUID organizationId,String aggregateType,UUID aggregateId,String eventType,Map<String,Object> payload) {
    UUID id=UUID.randomUUID();
    try {
      jdbc.sql("INSERT INTO outbox_events(id,organization_id,aggregate_type,aggregate_id,event_type,payload) VALUES (:id,:org,:aggregate,:aggregateId,:eventType,CAST(:payload AS jsonb))")
        .param("id",id).param("org",organizationId).param("aggregate",aggregateType).param("aggregateId",aggregateId).param("eventType",eventType).param("payload",json.writeValueAsString(payload)).update();
      return id;
    } catch (JsonProcessingException error) { throw new IllegalArgumentException("Unable to serialize outbox event",error); }
  }
}
