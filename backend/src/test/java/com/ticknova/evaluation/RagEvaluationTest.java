package com.ticknova.evaluation;

import com.ticknova.ai.AiIndexWorker;
import com.ticknova.ai.AiRetriever;
import com.ticknova.ai.AiWorker;
import com.ticknova.ai.model.AiModel;
import com.ticknova.identity.SessionTokens;
import com.ticknova.shared.Actor;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import org.assertj.core.api.SoftAssertions;
import static org.assertj.core.api.Assertions.*;

/** Candidate-labelled deterministic regression. This does not measure real-model quality. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "ticknova.ai.worker-enabled=false", "ticknova.knowledge.worker-enabled=false"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class RagEvaluationTest {
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired AiIndexWorker indexer;
    @Autowired AiWorker worker;
    @Autowired AiRetriever retriever;
    @Autowired AiModel model;
    @Autowired SessionTokens tokens;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    record Fixture(UUID document,UUID version) {}

    @Test void fixedCandidateDatasetKeepsRecallCitationsAndRefusalWithinAuthorizedSources() throws Exception {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        assertThat(model.mode()).isEqualTo("mock");
        JsonNode dataset=json.readTree(Files.readString(Path.of("../eval/rag-cases.json")));
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        UUID reader=UUID.randomUUID(),space=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'eval-reader','评测请求者','unused',false)").param("id",reader).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code='REQUESTER'").param("id",reader).update();
        jdbc.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES(:s,'虚构G4评测资料',:u)").param("s",space).param("u",reader).update();
        jdbc.sql("INSERT INTO knowledge_space_member(space_id,user_id,can_read,can_edit) VALUES(:s,:u,true,false)").param("s",space).param("u",reader).update();
        Map<String,Fixture> fixtures=new LinkedHashMap<>();
        for(JsonNode doc:dataset.path("documents")) fixtures.put(doc.path("key").asText(),fixture(reader,space,doc.path("title").asText(),doc.path("content").asText()));
        for(int i=0;i<100&&indexer.runOnce();i++) { /* Each call persists a bounded batch. */ }
        Actor actor=new Actor(reader,"eval-reader","评测请求者",Set.of("REQUESTER"),false);
        var session=tokens.generate();
        jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:u,:h,now()+interval '30 minutes')").param("id",UUID.randomUUID()).param("u",reader).param("h",session.hash()).update();
        List<Map<String,Object>> rows=new ArrayList<>(); SoftAssertions checks=new SoftAssertions();
        int positives=0,recalled=0,refusals=0,wrongRefusals=0,citations=0,relevantCitations=0;
        Fixture gateway=fixtures.get("gateway");
        for(JsonNode test:dataset.path("cases")) {
            String scenario=test.path("scenario").asText("");
            boolean denied=scenario.equals("NO_ACL"),hidden=scenario.equals("WITHDRAWN")||scenario.equals("SUPERSEDED");
            if(denied) jdbc.sql("UPDATE knowledge_space_member SET can_read=false WHERE space_id=:s AND user_id=:u").param("s",space).param("u",reader).update();
            if(scenario.equals("WITHDRAWN")) jdbc.sql("UPDATE knowledge_document SET published_version_id=NULL,publication_state='DISABLED' WHERE id=:id").param("id",gateway.document()).update();
            if(scenario.equals("SUPERSEDED")) {
                addVersion(reader,gateway.document(),2,"本版只记录静态页面的背景颜色为浅蓝色。");
                for(int i=0;i<20&&indexer.runOnce();i++) { /* Index replacement while retaining old chunks. */ }
            }
            Set<UUID> expected=new HashSet<>();for(JsonNode key:test.path("expectedDocuments")) expected.add(fixtures.get(key.asText()).document());
            List<UUID> recalledDocs=denied?List.of():retriever.retrieve(actor,List.of(space),test.path("question").asText()).stream().limit(5).map(c->c.citation().documentId()).toList();
            if(!expected.isEmpty()){positives++;if(recalledDocs.stream().anyMatch(expected::contains))recalled++;}
            JsonNode created=request("POST","/ai/answers",session.token(),Map.of("question",test.path("question").asText(),"spaceIds",List.of()));
            UUID job=UUID.fromString(created.path("jobId").asText());
            for(int i=0;i<40&&worker.runOnce();i++) { /* Drain due work; no synthetic time advance. */ }
            JsonNode state=request("GET","/jobs/"+job,session.token(),null),result=state.path("result");
            String outcome=result.path("outcome").asText("");
            checks.assertThat(state.path("status").asText()).as(test.path("id").asText()+" job").isEqualTo("COMPLETED");
            checks.assertThat(outcome).as(test.path("id").asText()+" outcome").isEqualTo(test.path("outcome").asText());
            if(expected.isEmpty()){refusals++;if(!outcome.equals("INSUFFICIENT_EVIDENCE"))wrongRefusals++;}
            List<String> sourceIds=new ArrayList<>();
            for(JsonNode citation:result.path("citations")) {
                UUID doc=UUID.fromString(citation.path("documentId").asText());citations++;if(expected.contains(doc))relevantCitations++;
                sourceIds.add(doc.toString());
                checks.assertThat(denied).as("no denied source "+test.path("id").asText()).isFalse();
                checks.assertThat(!hidden||!doc.equals(gateway.document())).as("no withdrawn source "+test.path("id").asText()).isTrue();
                checks.assertThat(fixtures.values().stream().anyMatch(f->f.document().equals(doc))).as("known citation source").isTrue();
                if(!expected.isEmpty()) checks.assertThat(expected).as("candidate-labelled citation "+test.path("id").asText()).contains(doc);
            }
            rows.add(Map.of("id",test.path("id").asText(),"expected",test.path("outcome").asText(),"actual",outcome,"status",state.path("status").asText(),"recalledDocumentIds",recalledDocs,"citationDocumentIds",sourceIds));
            jdbc.sql("UPDATE knowledge_space_member SET can_read=true WHERE space_id=:s AND user_id=:u").param("s",space).param("u",reader).update();
            jdbc.sql("UPDATE knowledge_document SET publication_state='PUBLISHED',published_version_id=:v WHERE id=:id").param("v",gateway.version()).param("id",gateway.document()).update();
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("recordedAt",java.time.Instant.now().toString());report.put("dataset",dataset.path("dataset").asText());report.put("labelStatus",dataset.path("labelStatus").asText());
        report.put("provider",model.mode());report.put("profile",model.profile());report.put("cases",rows.size());report.put("positiveCases",positives);report.put("refusalCases",refusals);
        report.put("documents",fixtures.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().document().toString())));
        report.put("recallAt5",positives==0?0.0:(double)recalled/positives);report.put("candidateLabelCitationPrecision",citations==0?0.0:(double)relevantCitations/citations);report.put("refusalErrorRate",refusals==0?0.0:(double)wrongRefusals/refusals);
        report.put("results",rows);report.put("notRun",List.of("Real model semantic quality","Human groundedness/label review","Classification Macro-F1","Performance P95"));
        String evidenceTag=System.getenv("TICKNOVA_EVIDENCE_TAG");
        if(evidenceTag==null)evidenceTag="local";
        if(!evidenceTag.matches("[a-z0-9-]+"))throw new IllegalArgumentException("Invalid evidence tag");
        Path evidence=Path.of(System.getenv("TICKNOVA_EVIDENCE_TAG")==null?"../.cache/rag-evaluation.json":"../docs/evidence/"+evidenceTag+"-rag-evaluation.json");Files.createDirectories(evidence.getParent());
        try { checks.assertAll();report.put("status","PASS"); }
        catch(AssertionError failure) {report.put("status","FAILED");Files.writeString(evidence,json.writeValueAsString(report)+"\n");throw failure;}
        Files.writeString(evidence,json.writeValueAsString(report)+"\n");
    }
    private Fixture fixture(UUID user,UUID space,String title,String content) {
        UUID doc=UUID.randomUUID();
        jdbc.sql("INSERT INTO knowledge_document(id,space_id,title,created_by) VALUES(:d,:s,:t,:u)").param("d",doc).param("s",space).param("t",title).param("u",user).update();
        UUID version=addVersion(user,doc,1,content);
        return new Fixture(doc,version);
    }
    private UUID addVersion(UUID user,UUID doc,int number,String content) {
        UUID version=UUID.randomUUID();String hashPart=version.toString().replace("-","");
        // Synthetic historical publications are grandfathered just like the V5 upgrade fixtures.
        jdbc.sql("INSERT INTO document_version(id,document_id,version_no,file_name,mime_type,sha256,storage_key,file_size,ingest_state,publication_state,created_by,review_state,review_grandfathered,authored_title) VALUES(:v,:d,:number,'fixture.txt','text/plain',:h,:k,:size,'READY','PUBLISHED',:u,'APPROVED',true,(SELECT title FROM knowledge_document WHERE id=:d))")
            .param("v",version).param("d",doc).param("number",number).param("h",hashPart+hashPart).param("k",UUID.randomUUID()+".bin").param("size",content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).param("u",user).update();
        jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,page_no,content,strategy_version) VALUES(:id,:v,0,1,:text,'eval-fixture-v1')").param("id",UUID.randomUUID()).param("v",version).param("text",content).update();
        jdbc.sql("UPDATE knowledge_document SET latest_version_id=:v,published_version_id=:v,publication_state='PUBLISHED' WHERE id=:id").param("v",version).param("id",doc).update();
        return version;
    }
    private JsonNode request(String method,String path,String token,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString());
        var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method+" "+path).isIn(200,202);
        return json.readTree(response.body());
    }
}
