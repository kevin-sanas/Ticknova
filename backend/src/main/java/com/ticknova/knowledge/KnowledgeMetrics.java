package com.ticknova.knowledge;

import io.micrometer.core.instrument.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeMetrics {
    private final JdbcClient jdbc;
    private final MeterRegistry registry;
    public KnowledgeMetrics(JdbcClient jdbc,MeterRegistry registry) {
        this.jdbc=jdbc;this.registry=registry;
        gauge("pending","SELECT count(*) FROM outbox_event WHERE event_type='DOCUMENT_INGEST' AND state='PENDING'");
        gauge("processing","SELECT count(*) FROM outbox_event WHERE event_type='DOCUMENT_INGEST' AND state='PROCESSING'");
        gauge("dead","SELECT count(*) FROM outbox_event WHERE event_type='DOCUMENT_INGEST' AND state='DEAD'");
        gauge("retries","SELECT COALESCE(sum(GREATEST(attempts-1,0)),0) FROM outbox_event WHERE event_type='DOCUMENT_INGEST'");
        gauge("oldest_pending_seconds","SELECT COALESCE(EXTRACT(EPOCH FROM (now()-min(created_at))),0) FROM outbox_event WHERE event_type='DOCUMENT_INGEST' AND state='PENDING'");
    }
    private void gauge(String name,String query) {
        Gauge.builder("ticknova.knowledge.outbox."+name,this,ignored->{
            try{return jdbc.sql(query).query(Double.class).single();}catch(DataAccessException error){return Double.NaN;}
        }).register(registry);
    }
    public <T> T measure(String stage,Supplier<T> work) {
        long started=System.nanoTime();String outcome="failure";
        try{T result=work.get();outcome="success";return result;}
        finally{Timer.builder("ticknova.knowledge.ingest.stage").tag("stage",stage).tag("outcome",outcome).register(registry).record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}
    }
}
