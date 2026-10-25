package com.ticknova.ai;

import com.ticknova.ai.model.*;
import com.ticknova.knowledge.*;
import com.ticknova.shared.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit benchmark only (-Dtest=RagBenchmarkIT); never part of the 32-case regression. */
@SpringBootTest(properties={"ticknova.ai.worker-enabled=false","ticknova.knowledge.worker-enabled=false","ticknova.seed.enabled=false","ticknova.knowledge.upload-root=/tmp/ticknova-eval-uploads"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_RAG_BENCHMARK",matches="true")
class RagBenchmarkIT {
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired AiModel provider;
    @Autowired AiAuthorization authorization;
    @Autowired AiResultValidator validator;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeAuthoringService authoring;
    @Autowired KnowledgeWorker ingester;
    @Autowired PlatformTransactionManager transactions;
    record Document(UUID id,UUID version) {}
    final Map<String,Document> documents=new LinkedHashMap<>();

    @Test void benchmarkOriginalCorpus() throws Exception {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_eval");
        assertThat(jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()).isZero();
        if(!provider.mode().equals("mock")&&!Boolean.parseBoolean(System.getenv("EVAL_ALLOW_PAID")))throw new IllegalStateException("Paid benchmark requires an explicit approved invocation");
        byte[] bytes=Files.readAllBytes(Path.of("../eval/service-desk-cases.json"));JsonNode dataset=json.readTree(bytes);
        assertThat(dataset.path("cases").size()).isBetween(30,50);
        String selected=System.getenv().getOrDefault("EVAL_CASE_IDS","");Set<String> requested=selected.isBlank()?Set.of():new LinkedHashSet<>(Arrays.asList(selected.split(",")));
        var cases=new ArrayList<JsonNode>();for(JsonNode test:dataset.path("cases"))if(requested.isEmpty()||requested.contains(test.path("id").asText()))cases.add(test);
        if(!requested.isEmpty())assertThat(cases.stream().map(test->test.path("id").asText()).toList()).containsExactlyInAnyOrderElementsOf(requested);
        String selection=System.getenv().getOrDefault("EVAL_STRATEGY","ALL");assertThat(selection).isIn("ALL","HYBRID");
        var strategies=selection.equals("HYBRID")?List.of(AiRetriever.Strategy.HYBRID):Arrays.asList(AiRetriever.Strategy.values());
        var report=new LinkedHashMap<String,Object>();var results=new ArrayList<Map<String,Object>>();
        report.put("recordedAt",Instant.now().toString());report.put("dataset",dataset.path("dataset").asText());
        report.put("datasetSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.put("labelStatus",dataset.path("labelStatus").asText());report.put("mode",provider.mode());report.put("profile",provider.profile());
        report.put("chatModel",provider.mode().equals("mock")?"extractive-mock":System.getenv("AI_MODEL"));
        report.put("embeddingModel",provider.mode().equals("mock")?"hash-v1":System.getenv("EMBEDDING_MODEL"));report.put("embeddingDimensions",1024);
        report.put("scope","Real Docling + independent review/publish + production retrieval/context/validator; direct generation, not HTTP/outbox latency");
        report.put("results",results);report.put("status","FAILED");
        report.put("selectedCaseIds",cases.stream().map(test->test.path("id").asText()).toList());report.put("selectedStrategies",strategies);
        report.put("generationPolicy","source-specifics-v1");report.put("timingCaveat","Query embedding cache is reused; diagnostic timings do not compare strategy latency fairly");
        var model=new BudgetModel(provider);var retriever=new AiRetriever(jdbc,model,authorization);
        try {
            Actor admin=actor("benchmark-admin","ADMIN"),editor=actor("benchmark-editor","KB_EDITOR"),reviewer=actor("benchmark-reviewer","KB_REVIEWER"),reader=actor("benchmark-reader","REQUESTER"),outsider=actor("benchmark-outsider","REQUESTER");
            UUID space=knowledge.createSpace(admin,"原创评测知识",UUID.randomUUID().toString()).id();
            knowledge.membership(admin,space,editor.id(),true,true,"显式评测编辑成员");knowledge.membership(admin,space,reviewer.id(),true,false,true,"独立评测审核成员");knowledge.membership(admin,space,reader.id(),true,false,"评测阅读权限");
            for(JsonNode doc:dataset.path("documents"))documents.put(doc.path("key").asText(),create(editor,reviewer,space,doc.path("title").asText(),doc.path("paragraphs"),true));
            create(editor,reviewer,space,"未发布门禁领取说明",json.valueToTree(List.of("门禁访问凭据的临时领取地点为演示服务台A窗口。")),false);
            var indexer=new AiIndexWorker(jdbc,transactions,model,false,Integer.parseInt(System.getenv().getOrDefault("EMBEDDING_BATCH_SIZE","32")));
            for(int i=0;i<100&&indexer.runOnce();i++) { /* Bounded production batches, no automatic time advance/retry. */ }
            assertThat(jdbc.sql("SELECT count(*) FROM document_version WHERE embedding_state<>'READY'").query(Long.class).single()).as("All benchmark sources must really index").isZero();
            var sourceHashes=new LinkedHashMap<String,String>();
            for(var item:documents.entrySet())sourceHashes.put(item.getKey(),jdbc.sql("SELECT sha256 FROM document_version WHERE id=:id").param("id",item.getValue().version()).query(String.class).single());
            report.put("sourceSha256",sourceHashes);
            var metrics=new LinkedHashMap<String,Object>();report.put("strategies",metrics);
            for(var strategy:strategies) {
                int positives=0,hits=0,negative=0,falseAnswers=0,cited=0,relevant=0,failures=0;double recall=0;var durations=new ArrayList<Double>();
                for(JsonNode test:cases) {
                    String scenario=test.path("scenario").asText();var row=new LinkedHashMap<String,Object>();row.put("id",test.path("id").asText());row.put("question",test.path("question").asText());row.put("strategy",strategy.name());results.add(row);
                    Set<UUID> gold=evidence(test);row.put("expectedChunkIds",gold);boolean positive=!gold.isEmpty();
                    if(positive)positives++;else negative++;
                    Document withdrawn=scenario.equals("WITHDRAWN")?documents.get(test.path("withdrawDocument").asText()):null;
                    if(withdrawn!=null)knowledge.disable(editor,withdrawn.id());
                    try {
                        Actor current=scenario.equals("NO_ACL")?outsider:reader;List<AiRetriever.Candidate> candidates;
                        long start=System.nanoTime();
                        try {candidates=retriever.retrieve(current,List.of(space),test.path("question").asText(),strategy);}
                        catch(ApiException denied) {if(!scenario.equals("NO_ACL"))throw denied;candidates=List.of();row.put("authorizationRejected",true);}
                        double elapsed=(System.nanoTime()-start)/1_000_000.0;durations.add(elapsed);row.put("retrievalMs",elapsed);
                        var top=candidates.stream().limit(5).map(c->c.citation().chunkId()).collect(java.util.stream.Collectors.toSet());
                        long found=gold.stream().filter(top::contains).count();if(positive){recall+=(double)found/gold.size();if(found>0)hits++;}
                        for(var c:candidates)authorization.citation(current,c.citation().chunkId(),List.of(space));
                        var prepared=AiContextBudget.prepare("ANSWER",test.path("question").asText(),"","",List.of(),candidates,List.of());
                        AiDtos.AiResult result=prepared.sources().isEmpty()?validator.refusal(UUID.randomUUID(),"ANSWER",model.mode(),model.profile()):validator.validate(UUID.randomUUID(),"ANSWER",model.mode(),model.profile(),model.generate(prepared.input()),prepared.sources(),List.of());
                        for(var citation:result.citations()){authorization.citation(current,citation.chunkId(),List.of(space));cited++;if(gold.contains(citation.chunkId()))relevant++;}
                        if(!positive&&result.outcome().equals("ANSWERED"))falseAnswers++;
                        row.put("retrievedChunkIds",top);row.put("outcome",result.outcome());row.put("answer",Objects.toString(result.answer(),""));row.put("citations",result.citations());
                    } catch(AiModelFailure failure) {failures++;row.put("outcome","FAILED");row.put("errorCode",failure.code());}
                    finally {if(withdrawn!=null)knowledge.publish(editor,withdrawn.id(),withdrawn.version());}
                    System.out.println("Benchmark "+strategy+" "+test.path("id").asText()+": "+row.get("outcome"));
                }
                var summary=new LinkedHashMap<String,Object>();summary.put("cases",positives+negative);summary.put("positiveCases",positives);summary.put("refusalCases",negative);
                summary.put("chunkRecallAt5",positives==0?null:recall/positives);summary.put("hitAt5",positives==0?null:(double)hits/positives);
                summary.put("candidateLabelCitationPrecision",cited==0?null:(double)relevant/cited);summary.put("refusalFalseAnswerRate",negative==0?null:(double)falseAnswers/negative);
                summary.put("providerFailures",failures);summary.put("retrievalP95Ms",percentile(durations));metrics.put(strategy.name(),summary);
            }
            report.put("status","COMPLETED");report.put("qualityClaim",provider.mode().equals("mock")?"Deterministic mock benchmark only":"Real-provider measurements; candidate labels still require human review");
        } finally {
            report.put("calls",model.counters());report.put("notRun",List.of("Human relevance/groundedness review","Provider-billed token usage and cost reconciliation","HTTP/outbox end-to-end performance"));
            if(provider.mode().equals("mock"))report.put("realModelQuality","NOT RUN");
            String tag=provider.mode().equals("mock")?"mock":"real";String stamp=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
            Path target=Path.of("../docs/evidence/rag-benchmark-"+tag+"-"+stamp+".json");Files.createDirectories(target.getParent());Files.writeString(target,json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println("Benchmark evidence: "+target);
        }
    }
    private Actor actor(String username,String role){
        UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:name,:name,'benchmark-not-a-login-hash',false)").param("id",id).param("name",username).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code=:role").param("id",id).param("role",role).update();return new Actor(id,username,username,Set.of(role),false);
    }
    private Document create(Actor editor,Actor reviewer,UUID space,String title,JsonNode paragraphs,boolean publish){
        var nodes=new ArrayList<Map<String,Object>>();nodes.add(Map.of("type","heading","attrs",Map.of("level",1),"content",List.of(Map.of("type","text","text",title))));
        for(JsonNode p:paragraphs)nodes.add(Map.of("type","paragraph","content",List.of(Map.of("type","text","text",p.asText()))));
        var draft=authoring.create(editor,space,title,null,json.valueToTree(Map.of("type","doc","content",nodes)),UUID.randomUUID().toString());
        var upload=authoring.commit(editor,draft.id(),draft.version(),UUID.randomUUID().toString());assertThat(ingester.runOnce()).isTrue();
        assertThat(jdbc.sql("SELECT ingest_state FROM document_version WHERE id=:id").param("id",upload.versionId()).query(String.class).single()).isEqualTo("READY");
        if(publish){knowledge.requestReview(editor,draft.id(),upload.versionId());knowledge.review(reviewer,draft.id(),upload.versionId(),"APPROVED",null);knowledge.publish(editor,draft.id(),upload.versionId());}
        return new Document(draft.id(),upload.versionId());
    }
    private Set<UUID> evidence(JsonNode test){
        var ids=new LinkedHashSet<UUID>();
        for(JsonNode expected:test.path("expectedEvidence")){
            var document=documents.get(expected.path("document").asText());assertThat(document).isNotNull();String quote=expected.path("quote").asText().replaceAll("\\s","");
            var chunks=jdbc.sql("SELECT id,content FROM knowledge_chunk WHERE document_version_id=:id ORDER BY chunk_index").param("id",document.version()).query((r,n)->Map.entry(r.getObject("id",UUID.class),r.getString("content"))).list();
            var matched=chunks.stream().filter(c->c.getValue().replaceAll("\\s","").contains(quote)).toList();assertThat(matched).as("Evidence anchor must resolve exactly: "+test.path("id").asText()).hasSize(1);ids.add(matched.getFirst().getKey());
        }return ids;
    }
    private static Double percentile(List<Double> values){if(values.isEmpty())return null;var sorted=values.stream().sorted().toList();return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*.95)-1));}
    private static final class BudgetModel implements AiModel {
        final AiModel delegate;final Map<String,float[]> embeddings=new HashMap<>();int embeddingInputs,embeddingCalls,chatCalls;long inputChars,outputChars;
        final int maxInputs=Integer.parseInt(System.getenv().getOrDefault("EVAL_MAX_EMBEDDING_INPUTS","128")),maxChat=Integer.parseInt(System.getenv().getOrDefault("EVAL_MAX_CHAT_CALLS","120"));
        BudgetModel(AiModel delegate){this.delegate=delegate;}
        public String mode(){return delegate.mode();}public String profile(){return delegate.profile();}
        public List<float[]> embed(List<String> texts){
            if(texts.size()==1&&embeddings.containsKey(texts.getFirst()))return List.of(embeddings.get(texts.getFirst()).clone());
            if(embeddingInputs+texts.size()>maxInputs)throw new IllegalStateException("Embedding input budget exhausted");embeddingInputs+=texts.size();embeddingCalls++;
            var vectors=delegate.embed(texts);if(texts.size()==1)embeddings.put(texts.getFirst(),vectors.getFirst().clone());return vectors;
        }
        public Output generate(Input input){if(chatCalls>=maxChat)throw new IllegalStateException("Chat call budget exhausted");chatCalls++;inputChars+=input.question().length()+input.sources().stream().mapToInt(s->s.content().length()).sum();var output=delegate.generate(input);outputChars+=Objects.toString(output.answer(),"").length()+Objects.toString(output.summary(),"").length();return output;}
        Map<String,Object> counters(){return Map.of("embeddingInputItems",embeddingInputs,"embeddingAdapterCalls",embeddingCalls,"chatCalls",chatCalls,"inputTextChars",inputChars,"outputTextChars",outputChars,"maxEmbeddingInputs",maxInputs,"maxChatCalls",maxChat,"queryEmbeddingCache","Same query reused across VECTOR/HYBRID; corpus indexed once");}
    }
}
