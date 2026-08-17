package dev.ledgerforge.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisher {
  private final JdbcClient jdbc;
  public OutboxPublisher(JdbcClient jdbc) { this.jdbc=jdbc; }

  @Transactional
  public void publishPending() {
    List<OutboxRow> rows=jdbc.sql("SELECT id,organization_id,event_type,payload::text,failures_remaining FROM outbox_events WHERE status IN ('PENDING','FAILED') AND available_at<=now() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 50")
      .query((rs,n)->new OutboxRow(rs.getObject("id",UUID.class),rs.getObject("organization_id",UUID.class),rs.getString("event_type"),rs.getString("payload"),rs.getInt("failures_remaining"))).list();
    for (OutboxRow row:rows) {
      if(row.failuresRemaining()>0) {
        jdbc.sql("UPDATE outbox_events SET status='FAILED',attempts=attempts+1,failures_remaining=failures_remaining-1,last_error='Injected delivery failure',available_at=now()+make_interval(secs=>LEAST(60,power(2,attempts)::int)) WHERE id=:id").param("id",row.id()).update();
        continue;
      }
      try {
        jdbc.sql("INSERT INTO published_events(outbox_id,organization_id,event_type,payload) VALUES (:id,:org,:type,CAST(:payload AS jsonb)) ON CONFLICT (outbox_id) DO NOTHING")
          .param("id",row.id()).param("org",row.organizationId()).param("type",row.eventType()).param("payload",row.payload()).update();
        jdbc.sql("UPDATE outbox_events SET status='PUBLISHED',attempts=attempts+1,published_at=now(),last_error=NULL WHERE id=:id").param("id",row.id()).update();
      } catch (RuntimeException error) {
        jdbc.sql("UPDATE outbox_events SET status='FAILED',attempts=attempts+1,last_error=:error,available_at=now()+make_interval(secs=>LEAST(60,power(2,attempts)::int)) WHERE id=:id")
          .param("id",row.id()).param("error",error.getMessage()).update();
      }
    }
  }
  record OutboxRow(UUID id,UUID organizationId,String eventType,String payload,int failuresRemaining) {}
}
