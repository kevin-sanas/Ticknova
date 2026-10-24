package com.ticknova.ai;

import com.ticknova.ai.model.AiModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"ticknova.ai.worker-enabled=false","ticknova.knowledge.worker-enabled=false","ticknova.ai.embedding-batch-size=1"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class AiIndexBatchIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired AiIndexWorker worker;
    @MockitoBean AiModel model;

    @Test void eachClaimContainsOneProviderBatchSoCumulativeLatencyCannotExpireItsLease(){
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        UUID user=UUID.randomUUID(),space=UUID.randomUUID(),document=UUID.randomUUID(),version=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'batch-fixture','Batch fixture','not-a-login-hash',false)").param("id",user).update();
        jdbc.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES(:id,'Batch fixture',:user)").param("id",space).param("user",user).update();
        jdbc.sql("INSERT INTO knowledge_document(id,space_id,title,created_by) VALUES(:id,:space,'Batch fixture',:user)").param("id",document).param("space",space).param("user",user).update();
        jdbc.sql("INSERT INTO document_version(id,document_id,version_no,file_name,mime_type,sha256,storage_key,file_size,created_by,ingest_state,authored_title) VALUES(:id,:document,1,'fixture.txt','text/plain',:hash,:key,100,:user,'READY','Batch fixture')").param("id",version).param("document",document).param("hash","a".repeat(64)).param("key",version.toString()).param("user",user).update();
        for(int i=0;i<32;i++)jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,strategy_version) VALUES(:id,:version,:i,'Original batch fixture','test')").param("id",UUID.randomUUID()).param("version",version).param("i",i).update();
        when(model.profile()).thenReturn("batch-test:1024");
        doAnswer(call->{
            List<String> inputs=call.getArgument(0);
            // Deterministically simulate 5 seconds per provider request without wall-clock sleeps.
            // At batch-size 1, 32 sequential requests would consume 160 seconds of a 120s lease.
            jdbc.sql("UPDATE document_version SET embedding_locked_until=embedding_locked_until-(:seconds*interval '1 second') WHERE id=:id").param("seconds",5*inputs.size()).param("id",version).update();
            return inputs.stream().map(text->{float[] v=new float[1024];v[0]=1;return v;}).toList();
        }).when(model).embed(anyList());
        for(int expected=1;expected<=32;expected++){
            assertThat(worker.runOnce()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunk WHERE document_version_id=:id AND embedding IS NOT NULL").param("id",version).query(Long.class).single()).as("Progress must commit within each lease").isEqualTo((long)expected);
            assertThat(jdbc.sql("SELECT embedding_state FROM document_version WHERE id=:id").param("id",version).query(String.class).single()).isEqualTo(expected==32?"READY":"PENDING");
        }
        verify(model,times(32)).embed(argThat(inputs->inputs.size()==1));
        assertThat(worker.runOnce()).isFalse();
    }
}
