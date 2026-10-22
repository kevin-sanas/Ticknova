package com.ticknova.ai;

import com.ticknova.ai.model.*;
import com.ticknova.shared.*;
import java.util.*;
import java.sql.*;
import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ticknova.ai.AiDtos.*;

@SpringBootTest(properties={"ticknova.ai.worker-enabled=false","ticknova.knowledge.worker-enabled=false"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class AiIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiService service;
    @Autowired AiIndexWorker indexer;
    @Autowired AiWorker worker;
    @Autowired AiRetriever retriever;
    @Autowired AiAuthorization authorization;
    @MockitoBean AiModel model;
    Actor reader,other;UUID space,document,version,chunk;
    @BeforeEach void setup(){
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();reader=user("reader","REQUESTER");other=user("other","ADMIN");space=space(reader);
        when(model.profile()).thenReturn("test:1024");when(model.mode()).thenReturn("mock");
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();List<String> inputs=call.getArgument(0);return inputs.stream().map(s->vector()).toList();}).when(model).embed(anyList());
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();AiModel.Input i=call.getArgument(0);return new AiModel.Output("已验证知识回复","公开摘要",null,"NORMAL",List.of("测试"),i.sources().isEmpty()?List.of():List.of(i.sources().getFirst().chunkId()));}).when(model).generate(any());
    }
    @Test void creationIsIdempotentAtomicAndCreatorOnly(){
        Created first=ask("网关配置");assertThat(service.answer(reader,new AnswerRequest("网关配置",List.of()),"same")).isEqualTo(first);
        assertThat(count("ai_job")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(1);assertThat(count("ai_request")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_REQUESTED'").query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(()->service.job(other,first.jobId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.answer(reader,new AnswerRequest("不同内容",List.of()),"same")).isInstanceOf(ApiException.class);
    }
    @Test void allReadableSnapshotDoesNotGrowAndRevocationBlocksEvenRefusalReplay(){
        Created first=ask("不存在的证据");UUID later=space(reader);
        assertThat(service.answer(reader,new AnswerRequest("不存在的证据",List.of()),"same")).isEqualTo(first);
        assertThat(jdbc.sql("SELECT cardinality(space_ids) FROM ai_request WHERE job_id=:id").param("id",first.jobId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(worker.runOnce()).isTrue();assertThat(service.job(reader,first.jobId()).result().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        revoke(space);assertThatThrownBy(()->service.job(reader,first.jobId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.answer(reader,new AnswerRequest("不存在的证据",List.of()),"same")).isInstanceOf(ApiException.class);
    }
    @Test void explicitSelectionIsLimitedButAllReadableSnapshotMayExceedTen(){
        for(int i=0;i<11;i++)space(reader);Created job=ask("问题");
        assertThat(jdbc.sql("SELECT cardinality(space_ids) FROM ai_request WHERE job_id=:id").param("id",job.jobId()).query(Integer.class).single()).isEqualTo(12);
        List<UUID> ids=jdbc.sql("SELECT id FROM knowledge_space").query(UUID.class).list();
        assertThatThrownBy(()->service.answer(reader,new AnswerRequest("问题",ids),"explicit")).isInstanceOf(ApiException.class);
    }
    @Test void noReadableSpacesIsAValidCompletedRefusal(){
        revoke(space);Created job=ask("网关配置");assertThat(worker.runOnce()).isTrue();
        assertThat(service.job(reader,job.jobId()).result().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");verify(model,never()).generate(any());
    }
    @Test void indexAndRetrievalAreProfilePublicationAndAclSafe(){
        fixture("网关配置 通过代理访问服务");assertThat(indexer.runOnce()).isTrue();
        assertThat(retriever.retrieve(reader,List.of(space),"网关配置")).hasSize(1);assertThat(retriever.retrieve(reader,List.of(space),"无关的香蕉价格")).isEmpty();
        assertThatThrownBy(()->retriever.retrieve(other,List.of(space),"网关配置")).isInstanceOf(ApiException.class);
        when(model.profile()).thenReturn("new:1024");assertThat(retriever.retrieve(reader,List.of(space),"网关配置")).isEmpty();
        assertThat(indexer.runOnce()).isTrue();assertThat(retriever.retrieve(reader,List.of(space),"网关配置")).hasSize(1);
        withdraw();assertThat(retriever.retrieve(reader,List.of(space),"网关配置")).isEmpty();
    }
    @Test void unapprovedPublishedPointerCannotEnterRecallOrGeneratedAnswer(){
        fixture("网关配置 通过代理访问服务",false);indexer.runOnce();
        assertThat(retriever.retrieve(reader,List.of(space),"网关配置")).isEmpty();
        Created job=ask("网关配置");worker.runOnce();
        assertThat(service.job(reader,job.jobId()).result().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(model,never()).generate(any());
    }
    @Test void unapprovedPublishedPointerCannotPassCitationRecheck(){
        fixture("网关配置 通过代理访问服务",false);
        assertThatThrownBy(()->authorization.citation(reader,chunk,List.of(space))).isInstanceOfSatisfying(ApiException.class,e->{
            assertThat(e.status()).isEqualTo(404);assertThat(e.code()).isEqualTo("SOURCE_UNAVAILABLE");
        });
    }
    @Test void citedAnswersAndFeedbackAreAuditedWithoutTicketWrites(){
        fixture("网关配置 通过代理访问服务");indexer.runOnce();Created job=ask("网关配置");worker.runOnce();AiResult r=service.job(reader,job.jobId()).result();
        assertThat(r.outcome()).isEqualTo("ANSWERED");assertThat(r.citations()).extracting(Citation::chunkId).containsExactly(chunk);assertThat(r.requiresHumanReview()).isTrue();
        service.feedback(reader,r.suggestionId(),new FeedbackRequest("ACCEPTED","好"));service.feedback(reader,r.suggestionId(),new FeedbackRequest("ACCEPTED",null));
        assertThat(service.job(reader,job.jobId()).result().feedback()).isEqualTo("ACCEPTED");assertThat(count("ticket_comment")).isZero();
        assertThatThrownBy(()->service.feedback(reader,r.suggestionId(),new FeedbackRequest("REJECTED",null))).isInstanceOf(ApiException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_FEEDBACK_RECORDED'").query(Long.class).single()).isEqualTo(1);
    }
    @Test void withdrawalAfterCompletionWithholdsWholeResponseAndAuditsOnce(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();withdraw();
        assertThatThrownBy(()->service.feedback(reader,suggestion,new FeedbackRequest("ACCEPTED",null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.job(reader,job.jobId())).isInstanceOf(ApiException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_RESULT_WITHHELD'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT feedback FROM ai_suggestion WHERE id=:id").param("id",suggestion).query(String.class).optional()).isEmpty();
    }
    @Test void revocationDuringGenerationPreventsPersistingModelText(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");
        doAnswer(call->{revoke(space);return new AiModel.Output("不得持久化的私有回复",null,null,null,List.of(),List.of(chunk));}).when(model).generate(any());
        worker.runOnce();assertThat(count("ai_suggestion")).isZero();assertThat(jdbc.sql("SELECT error_code FROM ai_job WHERE id=:id").param("id",job.jobId()).query(String.class).single()).isEqualTo("SOURCE_UNAVAILABLE");
    }
    @Test void withdrawalDuringGenerationPreventsPersistingModelText(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");doAnswer(call->{withdraw();return new AiModel.Output("不得持久化",null,null,null,List.of(),List.of(chunk));}).when(model).generate(any());
        worker.runOnce();assertThat(count("ai_suggestion")).isZero();assertThat(service.job(reader,job.jobId()).errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
    }
    @Test void forgedCitationIsACompletedRefusalWithoutUntrustedOutput(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");doReturn(new AiModel.Output("未经验证的泄漏",null,null,null,List.of(),List.of(UUID.randomUUID()))).when(model).generate(any());worker.runOnce();
        AiResult r=service.job(reader,job.jobId()).result();assertThat(r.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(r.answer()).isNull();assertThat(r.citations()).isEmpty();
    }
    @Test void requesterTicketInputNeverContainsInternalNotesAndSummaryNeedsNoKnowledge(){
        UUID ticket=ticket();Created job=service.draft(reader,ticket,new DraftRequest("SUMMARY",List.of()),"ticket-summary");worker.runOnce();
        var input=org.mockito.ArgumentCaptor.forClass(AiModel.Input.class);verify(model).generate(input.capture());assertThat(input.getValue().ticketTitle()).isEqualTo("公开工单");assertThat(input.getValue().publicReplies()).containsExactly("公开回复");assertThat(input.getValue().toString()).doesNotContain("内部秘密");
        assertThat(service.job(reader,job.jobId()).result().summary()).isEqualTo("公开摘要");assertThat(count("ticket_comment")).isEqualTo(2);
        Created reply=service.draft(reader,ticket,new DraftRequest("REPLY",List.of()),"ticket-reply");worker.runOnce();assertThat(service.job(reader,reply.jobId()).result().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    @Test void staleWorkerLeaseCannotOverwriteNewCompletion(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==1){jdbc.sql("UPDATE outbox_event SET locked_until=now()-interval '1 second' WHERE job_id=:id").param("id",job.jobId()).update();assertThat(worker.runOnce()).isTrue();return new AiModel.Output("陈旧回答",null,null,null,List.of(),List.of(chunk));}return new AiModel.Output("新租约回答",null,null,null,List.of(),List.of(chunk));}).when(model).generate(any());
        worker.runOnce();assertThat(count("ai_suggestion")).isEqualTo(1);assertThat(service.job(reader,job.jobId()).result().answer()).isEqualTo("新租约回答");assertThat(service.job(reader,job.jobId()).attemptCount()).isEqualTo(2);
    }
    @Test void workerRetriesAreFiniteAndNeverExposeProviderErrorBody(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");doThrow(new AiModelFailure("AI_PROVIDER_UNAVAILABLE",true)).when(model).generate(any());
        for(int i=0;i<5;i++){assertThat(worker.runOnce()).isTrue();jdbc.sql("UPDATE outbox_event SET available_at=now() WHERE job_id=:id").param("id",job.jobId()).update();}
        Job failed=service.job(reader,job.jobId());assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.attemptCount()).isEqualTo(5);assertThat(failed.result()).isNull();assertThat(worker.runOnce()).isFalse();
    }
    @Test void indexerResumesAcross32ChunkAnd64kBudgets(){
        fixture("网关配置"+"文本".repeat(1000));
        for(int i=1;i<40;i++)jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,strategy_version) VALUES(:id,:v,:i,:text,'test')").param("id",UUID.randomUUID()).param("v",version).param("i",i).param("text","网关配置"+"文本".repeat(1000)).update();
        assertThat(indexer.runOnce()).isTrue();assertThat(jdbc.sql("SELECT embedding_state FROM document_version WHERE id=:id").param("id",version).query(String.class).single()).isEqualTo("PENDING");assertThat(indexer.runOnce()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunk WHERE embedding_profile='test:1024'").query(Long.class).single()).isEqualTo(40);assertThat(jdbc.sql("SELECT embedding_state FROM document_version WHERE id=:id").param("id",version).query(String.class).single()).isEqualTo("READY");
    }
    @Test void indexerFencesExpiredClaimAndValidatesDimensions(){
        fixture("网关配置");AtomicInteger calls=new AtomicInteger();doAnswer(call->{if(calls.incrementAndGet()==1){jdbc.sql("UPDATE document_version SET embedding_locked_until=now()-interval '1 second' WHERE id=:id").param("id",version).update();indexer.runOnce();return List.of(new float[]{1});}return List.of(vector());}).when(model).embed(anyList());
        indexer.runOnce();assertThat(jdbc.sql("SELECT embedding_state FROM document_version WHERE id=:id").param("id",version).query(String.class).single()).isEqualTo("READY");
    }
    @Test void auditFailureRollsBackJobAndOutboxCreation(){
        jdbc.sql("ALTER TABLE audit_event ADD CONSTRAINT ai_test_audit_guard CHECK(action <> 'AI_REQUESTED')").update();
        try{assertThatThrownBy(()->ask("网关配置")).isInstanceOf(RuntimeException.class);assertThat(count("ai_job")).isZero();assertThat(count("outbox_event")).isZero();assertThat(count("ai_request")).isZero();}finally{jdbc.sql("ALTER TABLE audit_event DROP CONSTRAINT ai_test_audit_guard").update();}
    }
    @Test void completionAuditFailureRollsBackSuggestionAndRetries(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");jdbc.sql("ALTER TABLE audit_event ADD CONSTRAINT ai_test_audit_guard CHECK(action <> 'AI_REQUEST_COMPLETED')").update();
        try{worker.runOnce();assertThat(count("ai_suggestion")).isZero();assertThat(service.job(reader,job.jobId()).status()).isEqualTo("RETRYING");}finally{jdbc.sql("ALTER TABLE audit_event DROP CONSTRAINT ai_test_audit_guard").update();}
        jdbc.sql("UPDATE outbox_event SET available_at=now() WHERE job_id=:id").param("id",job.jobId()).update();worker.runOnce();assertThat(count("ai_suggestion")).isEqualTo(1);
    }
    @Test void concurrentWorkersProduceOneSuggestion() throws Exception {
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");ExecutorService pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->worker.runOnce());var b=pool.submit(()->worker.runOnce());a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(count("ai_suggestion")).isEqualTo(1);assertThat(service.job(reader,job.jobId()).status()).isEqualTo("COMPLETED");
    }
    @Test void everyOriginalScopeIsRevalidatedEvenWhenOnlyOneWasCited(){
        fixture("网关配置");UUID unused=space(reader);indexer.runOnce();Created job=ask("网关配置");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();revoke(unused);
        assertThatThrownBy(()->service.feedback(reader,suggestion,new FeedbackRequest("REJECTED",null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.job(reader,job.jobId())).isInstanceOf(ApiException.class);
    }
    @Test void replacingPublishedPointerInvalidatesOldCitations(){
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");worker.runOnce();UUID replacement=UUID.randomUUID();
        jdbc.sql("INSERT INTO document_version(id,document_id,version_no,file_name,mime_type,sha256,storage_key,file_size,created_by,ingest_state,publication_state,review_state,review_grandfathered,authored_title) VALUES(:v,:d,2,'new.txt','text/plain',:hash,:key,20,:u,'READY','PUBLISHED','APPROVED',true,'新指南')").param("v",replacement).param("d",document).param("hash","b".repeat(64)).param("key",replacement.toString()).param("u",reader.id()).update();
        jdbc.sql("UPDATE knowledge_document SET published_version_id=:v WHERE id=:d").param("v",replacement).param("d",document).update();
        assertThatThrownBy(()->service.job(reader,job.jobId())).isInstanceOf(ApiException.class);
    }
    @Test void malformedEmbeddingFailsImmediatelyAndRetryableFailuresStopAtFive(){
        fixture("网关配置");doReturn(List.of(new float[]{1})).when(model).embed(anyList());
        assertThat(indexer.runOnce()).isTrue();assertThat(indexer.runOnce()).isFalse();
        assertThat(jdbc.sql("SELECT embedding_state FROM document_version WHERE id=:id").param("id",version).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT embedding IS NULL FROM knowledge_chunk WHERE id=:id").param("id",chunk).query(Boolean.class).single()).isTrue();
        when(model.profile()).thenReturn("retry:1024");doThrow(new AiModelFailure("AI_PROVIDER_UNAVAILABLE",true)).when(model).embed(anyList());
        for(int i=0;i<5;i++){assertThat(indexer.runOnce()).isTrue();jdbc.sql("UPDATE document_version SET embedding_available_at=now() WHERE id=:id").param("id",version).update();}
        assertThat(indexer.runOnce()).isFalse();assertThat(jdbc.sql("SELECT embedding_attempts FROM document_version WHERE id=:id").param("id",version).query(Integer.class).single()).isEqualTo(5);
    }
    @Test void weakGenericOverlapCannotAnswerAnUnrelatedQuestion(){
        fixture("先检查打印机电源和纸张，再安装打印驱动");indexer.runOnce();
        assertThat(retriever.retrieve(reader,List.of(space),"网关无法连接时先检查哪些代理设置")).isEmpty();
    }
    @Test void fullChunkTailLexicalEvidenceWorksIndependentlyOfCosine(){
        StringBuilder text=new StringBuilder();for(int i=0;i<400;i++)text.append("word").append(i).append(' ');text.append("末尾网关配置资料");fixture(text.toString());
        doAnswer(call->{List<String> inputs=call.getArgument(0);return inputs.stream().map(s->{float[] v=new float[1024];v[s.length()<100?1:0]=1;return v;}).toList();}).when(model).embed(anyList());
        indexer.runOnce();assertThat(retriever.retrieve(reader,List.of(space),"网关配置资料")).hasSize(1);
    }
    @Test void accountDisableAfterCompletionPreventsReadingSavedResult(){
        Created job=ask("无证据");worker.runOnce();jdbc.sql("UPDATE app_user SET active=false WHERE id=:id").param("id",reader.id()).update();
        assertThatThrownBy(()->service.job(reader,job.jobId())).isInstanceOf(ApiException.class);
    }
    @Test void feedbackAuditFailureRollsBackChoice(){
        Created job=ask("无证据");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();jdbc.sql("ALTER TABLE audit_event ADD CONSTRAINT ai_test_audit_guard CHECK(action <> 'AI_FEEDBACK_RECORDED')").update();
        try{assertThatThrownBy(()->service.feedback(reader,suggestion,new FeedbackRequest("ACCEPTED",null))).isInstanceOf(RuntimeException.class);assertThat(service.job(reader,job.jobId()).result().feedback()).isNull();}finally{jdbc.sql("ALTER TABLE audit_event DROP CONSTRAINT ai_test_audit_guard").update();}
    }
    @Test void reviewTicketOnlyKindsNeverRetrieveOrSupplyKnowledge(){
        fixture("公开工单 公开描述 知识秘密");indexer.runOnce();UUID ticket=ticket();clearInvocations(model);
        doAnswer(call->{AiModel.Input input=call.getArgument(0);assertThat(input.sources()).isEmpty();return new AiModel.Output(null,"公开摘要",null,"NORMAL",List.of(),List.of());}).when(model).generate(any());
        for(String kind:List.of("SUMMARY","CLASSIFICATION")){Created job=service.draft(reader,ticket,new DraftRequest(kind,List.of()),"ticket-only-"+kind);worker.runOnce();assertThat(service.job(reader,job.jobId()).status()).isEqualTo("COMPLETED");}
        verify(model,never()).embed(anyList());
    }
    @Test void reviewEverySuppliedAnswerSourceIsCheckedBeforeCompletion(){
        fixture("网关配置第一段");fixture("网关配置第二段");while(indexer.runOnce()){}Created job=ask("网关配置");
        doAnswer(call->{AiModel.Input input=call.getArgument(0);assertThat(input.sources()).hasSize(2);withdrawChunk(input.sources().get(1).chunkId());return new AiModel.Output("依赖两个来源的回答",null,null,null,List.of(),List.of(input.sources().getFirst().chunkId()));}).when(model).generate(any());
        worker.runOnce();assertThat(count("ai_suggestion")).isZero();assertThat(service.job(reader,job.jobId()).errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
    }
    @Test void reviewUncitedSuppliedAnswerSourceIsCheckedOnReadAndFeedback(){reviewHiddenSourceAfterCompletion("ANSWER");}
    @Test void reviewUncitedSuppliedReplySourceIsCheckedOnReadAndFeedback(){reviewHiddenSourceAfterCompletion("REPLY");}
    private void reviewHiddenSourceAfterCompletion(String kind){
        fixture("网关配置第一段 公开工单 公开描述");fixture("网关配置第二段 公开工单 公开描述");while(indexer.runOnce()){}AtomicReference<UUID> uncited=new AtomicReference<>();
        doAnswer(call->{AiModel.Input input=call.getArgument(0);assertThat(input.sources()).hasSize(2);uncited.set(input.sources().get(1).chunkId());return new AiModel.Output("依赖两个来源的回答",null,null,null,List.of(),List.of(input.sources().getFirst().chunkId()));}).when(model).generate(any());
        Created job=kind.equals("ANSWER")?ask("网关配置"):service.draft(reader,ticket(),new DraftRequest("REPLY",List.of()),"reply-hidden");worker.runOnce();AiResult result=service.job(reader,job.jobId()).result();assertThat(result.citations()).hasSize(1);withdrawChunk(uncited.get());
        assertThatThrownBy(()->service.feedback(reader,result.suggestionId(),new FeedbackRequest("ACCEPTED",null))).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(404);assertThat(e.code()).isEqualTo("SOURCE_UNAVAILABLE");});
        assertThatThrownBy(()->service.job(reader,job.jobId())).isInstanceOf(ApiException.class);assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_RESULT_WITHHELD'").query(Long.class).single()).isEqualTo(1);
    }
    @Test void reviewLegalLargeTicketsFitEmbeddingAndGenerationBoundsForEveryKind(){
        fixture("公开工单 网关配置 安全末尾 公公");indexer.runOnce();UUID ticket=ticket();clearInvocations(model);
        doAnswer(call->{List<String> inputs=call.getArgument(0);if(inputs.stream().anyMatch(s->s.length()>8192))throw new AiModelFailure("AI_INPUT_TOO_LARGE",false);assertThat(inputs).allSatisfy(s->assertThat(s.length()).isLessThanOrEqualTo(4000));return inputs.stream().map(s->vector()).toList();}).when(model).embed(anyList());
        AtomicInteger expectedLength=new AtomicInteger();
        doAnswer(call->{AiModel.Input input=call.getArgument(0);assertThat(input.ticketDescription()).hasSize(expectedLength.get());assertThat(inputChars(input)).isLessThanOrEqualTo(60000);return new AiModel.Output("合法回复","合法摘要",null,"NORMAL",List.of(),input.sources().isEmpty()?List.of():List.of(input.sources().getFirst().chunkId()));}).when(model).generate(any());
        for(int size:List.of(8192,8193,20000)){
            String description="网关配置"+"公".repeat(size-8)+"安全末尾";expectedLength.set(size);jdbc.sql("UPDATE ticket SET description=:d WHERE id=:id").param("d",description).param("id",ticket).update();
            for(String kind:List.of("REPLY","SUMMARY","CLASSIFICATION")){Created job=service.draft(reader,ticket,new DraftRequest(kind,List.of()),"large-"+size+kind);worker.runOnce();assertThat(service.job(reader,job.jobId()).status()).as(size+" "+kind).isEqualTo("COMPLETED");}
        }
        verify(model,times(3)).embed(anyList());verify(model,times(9)).generate(any());
    }
    @Test void reviewClassificationAllocatesTwoHundredLongServicesAndPublicReplies(){
        UUID ticket=ticket();jdbc.sql("UPDATE ticket SET description=:d WHERE id=:id").param("d","公".repeat(20000)).param("id",ticket).update();
        List<UUID> temporaryServices=new ArrayList<>();
        try{
        for(int i=0;i<200;i++){UUID id=UUID.randomUUID();temporaryServices.add(id);jdbc.sql("INSERT INTO service_catalog(id,code,name) VALUES(:id,:code,:name)").param("id",id).param("code","ai-review-budget-"+i).param("name","服".repeat(160)).update();}
        for(int i=0;i<4;i++)jdbc.sql("INSERT INTO ticket_comment(id,ticket_id,author_id,visibility,body) VALUES(:id,:t,:u,'PUBLIC_REPLY',:body)").param("id",UUID.randomUUID()).param("t",ticket).param("u",reader.id()).param("body","回".repeat(10000)).update();
        doAnswer(call->{AiModel.Input input=call.getArgument(0);assertThat(input.sources()).isEmpty();assertThat(input.ticketDescription()).hasSize(20000);assertThat(inputChars(input)).isLessThanOrEqualTo(60000);assertThat(input.services()).isNotEmpty();return new AiModel.Output(null,null,input.services().getFirst().id(),"NORMAL",List.of("测试"),List.of());}).when(model).generate(any());
        Created job=service.draft(reader,ticket,new DraftRequest("CLASSIFICATION",List.of()),"catalog-budget");worker.runOnce();assertThat(service.job(reader,job.jobId()).status()).isEqualTo("COMPLETED");assertThat(service.job(reader,job.jobId()).result().outcome()).isEqualTo("ANSWERED");verify(model,never()).embed(anyList());
        }finally{if(!temporaryServices.isEmpty())jdbc.sql("DELETE FROM service_catalog WHERE id IN (:ids)").param("ids",temporaryServices).update();}
    }
    @Test void reviewConcurrentSameFeedbackHasTwoSuccessesAndOneAudit() throws Exception {
        Created job=ask("无证据");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();
        assertThat(overlap(suggestion,()->feedbackStatus(suggestion,"ACCEPTED"),()->feedbackStatus(suggestion,"ACCEPTED"))).containsExactlyInAnyOrder(204,204);assertThat(auditCount("AI_FEEDBACK_RECORDED")).isEqualTo(1);
    }
    @Test void reviewConcurrentOppositeFeedbackHasSuccessConflictAndOneAudit() throws Exception {
        Created job=ask("无证据");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();
        assertThat(overlap(suggestion,()->feedbackStatus(suggestion,"ACCEPTED"),()->feedbackStatus(suggestion,"REJECTED"))).containsExactlyInAnyOrder(204,409);assertThat(auditCount("AI_FEEDBACK_RECORDED")).isEqualTo(1);
    }
    @Test void reviewConcurrentWithdrawnReadAndFeedbackHaveNo500AndOneAudit() throws Exception {
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();withdraw();
        assertThat(overlap(suggestion,()->jobStatus(job.jobId()),()->feedbackStatus(suggestion,"ACCEPTED"))).containsExactlyInAnyOrder(404,404);assertThat(auditCount("AI_RESULT_WITHHELD")).isEqualTo(1);
    }
    @Test void reviewSerializationRetryStartsFreshTransactionsAndCommitsWithholdAudit() {
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();withdraw();installSerializationFailure(2);
        try{assertThat(jobStatus(job.jobId())).isEqualTo(404);assertThat(jdbc.sql("SELECT last_value FROM ai_test_retry_seq").query(Long.class).single()).isEqualTo(3);assertThat(auditCount("AI_RESULT_WITHHELD")).isEqualTo(1);}finally{removeSerializationFailure();}
    }
    @Test void reviewSerializationRetryStopsAfterThreeAttemptsWithStableConflict() {
        fixture("网关配置");indexer.runOnce();Created job=ask("网关配置");worker.runOnce();UUID suggestion=service.job(reader,job.jobId()).result().suggestionId();withdraw();installSerializationFailure(5);
        try{assertThat(jobStatus(job.jobId())).isEqualTo(409);assertThat(jdbc.sql("SELECT last_value FROM ai_test_retry_seq").query(Long.class).single()).isEqualTo(3);assertThat(auditCount("AI_RESULT_WITHHELD")).isZero();}finally{removeSerializationFailure();}
    }
    private void withdrawChunk(UUID id){jdbc.sql("UPDATE knowledge_document SET publication_state='DISABLED',published_version_id=NULL WHERE id=(SELECT v.document_id FROM document_version v JOIN knowledge_chunk c ON c.document_version_id=v.id WHERE c.id=:id)").param("id",id).update();}
    private long auditCount(String action){return jdbc.sql("SELECT count(*) FROM audit_event WHERE action=:a").param("a",action).query(Long.class).single();}
    private static long inputChars(AiModel.Input input){long chars=(input.question()==null?0:input.question().length())+input.ticketTitle().length()+input.ticketDescription().length();for(String s:input.publicReplies())chars+=s.length();for(var s:input.sources())chars+=s.content().length();for(var s:input.services())chars+=s.name().length()+36;return chars;}
    private int feedbackStatus(UUID id,String choice){try{service.feedback(reader,id,new FeedbackRequest(choice,null));return 204;}catch(ApiException e){return e.status();}catch(Exception e){return 500;}}
    private int jobStatus(UUID id){try{service.job(reader,id);return 200;}catch(ApiException e){return e.status();}catch(Exception e){return 500;}}
    private List<Integer> overlap(UUID suggestion,Callable<Integer> first,Callable<Integer> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Connection lock=dataSource.getConnection()){
            lock.setAutoCommit(false);try(PreparedStatement statement=lock.prepareStatement("SELECT id FROM ai_suggestion WHERE id=? FOR UPDATE")){statement.setObject(1,suggestion);statement.executeQuery().close();}
            Future<Integer> one=pool.submit(first),two=pool.submit(second);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);long blocked=0;
            while(System.nanoTime()<deadline){blocked=jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE 'SELECT s.job_id FROM ai_suggestion%' OR query LIKE 'UPDATE ai_suggestion SET withheld=true%')").query(Long.class).single();if(blocked>=2)break;Thread.sleep(10);}
            assertThat(blocked).as("both repeatable-read snapshots overlap behind the held suggestion row").isEqualTo(2);lock.commit();return List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    private void installSerializationFailure(int failures){
        jdbc.sql("CREATE SEQUENCE ai_test_retry_seq").update();
        jdbc.sql("CREATE FUNCTION ai_test_serialize() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF nextval(''ai_test_retry_seq'') <= "+failures+" THEN RAISE EXCEPTION ''test retry'' USING ERRCODE = ''40001''; END IF; RETURN NEW; END'").update();
        jdbc.sql("CREATE TRIGGER ai_test_serialize_trigger BEFORE UPDATE ON ai_suggestion FOR EACH ROW EXECUTE FUNCTION ai_test_serialize()").update();
    }
    private void removeSerializationFailure(){jdbc.sql("DROP TRIGGER ai_test_serialize_trigger ON ai_suggestion").update();jdbc.sql("DROP FUNCTION ai_test_serialize()").update();jdbc.sql("DROP SEQUENCE ai_test_retry_seq").update();}
    private Created ask(String q){return service.answer(reader,new AnswerRequest(q,List.of()),"same");}
    private long count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single();}
    private static float[] vector(){float[] v=new float[1024];v[0]=1;return v;}
    private Actor user(String name,String role){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:name,:name,'unused',false)").param("id",id).param("name",name).update();jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code=:role").param("id",id).param("role",role).update();return new Actor(id,name,name,Set.of(role),false);}
    private UUID space(Actor actor){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES(:s,'测试空间',:u)").param("s",id).param("u",actor.id()).update();jdbc.sql("INSERT INTO knowledge_space_member(space_id,user_id,can_read) VALUES(:s,:u,true)").param("s",id).param("u",actor.id()).update();return id;}
    private void revoke(UUID id){jdbc.sql("UPDATE knowledge_space_member SET can_read=false,can_edit=false WHERE space_id=:s AND user_id=:u").param("s",id).param("u",reader.id()).update();}
    private void withdraw(){jdbc.sql("UPDATE knowledge_document SET publication_state='DISABLED',published_version_id=NULL WHERE id=:id").param("id",document).update();}
    private UUID ticket(){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO ticket(id,title,description,requester_id,queue_id) SELECT :id,'公开工单','公开描述',:u,id FROM support_queue WHERE code='GENERAL'").param("id",id).param("u",reader.id()).update();for(String visibility:List.of("PUBLIC_REPLY","INTERNAL_NOTE"))jdbc.sql("INSERT INTO ticket_comment(id,ticket_id,author_id,visibility,body) VALUES(:id,:ticket,:u,:v,:body)").param("id",UUID.randomUUID()).param("ticket",id).param("u",reader.id()).param("v",visibility).param("body",visibility.equals("PUBLIC_REPLY")?"公开回复":"内部秘密").update();return id;}
    private void fixture(String content){fixture(content,true);}
    private void fixture(String content,boolean approved){document=UUID.randomUUID();version=UUID.randomUUID();chunk=UUID.randomUUID();jdbc.sql("INSERT INTO knowledge_document(id,space_id,title,created_by) VALUES(:d,:s,'网关指南',:u)").param("d",document).param("s",space).param("u",reader.id()).update();jdbc.sql("INSERT INTO document_version(id,document_id,version_no,file_name,mime_type,sha256,storage_key,file_size,created_by,ingest_state,publication_state,review_state,review_grandfathered,authored_title) VALUES(:v,:d,1,'guide.txt','text/plain',:hash,:key,100,:u,'READY','PUBLISHED',:review,:approved,'网关指南')").param("v",version).param("d",document).param("hash","a".repeat(64)).param("key",version.toString()).param("u",reader.id()).param("review",approved ? "APPROVED" : "DRAFT").param("approved",approved).update();jdbc.sql("UPDATE knowledge_document SET latest_version_id=:v,published_version_id=:v,publication_state='PUBLISHED' WHERE id=:d").param("v",version).param("d",document).update();jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,strategy_version) VALUES(:id,:v,0,:content,'test')").param("id",chunk).param("v",version).param("content",content).update();}
}



