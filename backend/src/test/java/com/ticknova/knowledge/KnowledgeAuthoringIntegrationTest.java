package com.ticknova.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeAuthoringIntegrationTest extends KnowledgeIntegrationTest {
    @Test void allowedActionsReflectParseReviewPublicationAndIndexLeaseStates() throws Exception {
        String space=createSpace();
        Reply first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"actions.txt","text/plain","actions");
        String doc=first.body().path("documentId").asText(),version=first.body().path("versionId").asText(),base="/knowledge/documents/"+doc;
        Reply queued=call("GET",base,editorToken,null);
        assertThat(queued.body().path("allowedActions").toString()).contains("EDIT_DRAFT","UPLOAD_VERSION","ARCHIVE").doesNotContain("SUBMIT_REVIEW","APPROVE","PUBLISH","RETRY_PARSE","RETRY_INDEX","DISABLE");
        jdbc.sql("UPDATE document_version SET ingest_state='FAILED' WHERE id=CAST(:id AS uuid)").param("id",version).update();
        assertThat(call("GET",base,editorToken,null).body().path("versions").get(0).path("allowedActions").toString()).contains("RETRY_PARSE");
        ready(version,"actions");
        Reply readyDetail=call("GET",base,editorToken,null);
        assertThat(readyDetail.body().path("allowedActions").toString()).contains("EDIT_DRAFT","UPLOAD_VERSION","ARCHIVE").doesNotContain("SUBMIT_REVIEW","APPROVE","REJECT","PUBLISH","RETRY_PARSE","RETRY_INDEX");
        assertThatThrownBy(()->knowledge.publish(new com.ticknova.shared.Actor(editor,"editor","Editor",Set.of("KB_EDITOR"),false),UUID.fromString(doc),UUID.fromString(version)))
            .isInstanceOf(com.ticknova.shared.ApiException.class).hasMessageContaining("专职知识审核者审核通过");
        jdbc.sql("UPDATE document_version SET embedding_state='FAILED',embedding_locked_until=now()+interval '1 hour' WHERE id=CAST(:id AS uuid)").param("id",version).update();
        assertThat(call("GET",base,editorToken,null).body().path("versions").get(0).path("allowedActions").toString()).contains("SUBMIT_REVIEW").doesNotContain("RETRY_INDEX");
        assertThat(call("POST",base+"/versions/"+version+"/index/retry",editorToken,null).status()).isEqualTo(409);
        jdbc.sql("UPDATE document_version SET embedding_locked_until=NULL WHERE id=CAST(:id AS uuid)").param("id",version).update();
        assertThat(call("GET",base,editorToken,null).body().path("versions").get(0).path("allowedActions").toString()).contains("RETRY_INDEX");
        approve(space,doc,version);
        assertThat(call("GET",base,editorToken,null).body().path("versions").get(0).path("allowedActions").toString()).contains("PUBLISH").doesNotContain("APPROVE","SUBMIT_REVIEW");
        assertThat(call("POST",base+"/versions/"+version+"/publish",editorToken,null).body().path("allowedActions").toString()).contains("DISABLE");
    }
    Map<String,Object> content(String text) {return Map.of("type","doc","content",List.of(Map.of("type","paragraph","content",List.of(Map.of("type","text","text",text)))));}
    Map<String,Object> article(String title,UUID category,String text,Integer version) {
        var body=new HashMap<String,Object>();body.put("title",title);body.put("categoryId",category);body.put("content",content(text));if(version!=null)body.put("version",version);return body;
    }
    @Test void unpublishedFileSeedsEditableDraftWithoutWritingUntilSaved() throws Exception {
        String space=createSpace();grant(space,reader,true,false);
        Reply upload=upload("/knowledge/spaces/"+space+"/documents",editorToken,"source.txt","text/plain","parsed source");
        String doc=upload.body().path("documentId").asText(),version=upload.body().path("versionId").asText();ready(version,"parsed source");
        String base="/knowledge/documents/"+doc;
        Reply draft=call("GET",base+"/draft",editorToken,null);
        assertThat(draft.status()).isEqualTo(200);
        assertThat(draft.body().path("version").asInt()).isZero();
        assertThat(draft.body().path("content").toString()).contains("parsed source");
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_draft WHERE document_id=CAST(:id AS uuid)").param("id",doc).query(Long.class).single()).isZero();
        assertThat(call("GET",base+"/draft",readerToken,null).status()).isEqualTo(404);
        Reply preview=call("GET",base+"/versions/"+version+"/draft-source",editorToken,null);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().path("importable").asBoolean()).isTrue();
        assertThat(preview.body().path("content").toString()).contains("parsed source");
        assertThat(call("GET",base+"/versions/"+version+"/draft-source",readerToken,null).status()).isEqualTo(404);
    }
    @Test void existingEmptyDraftNeedsExplicitVersionedReplacementAndSourceRemovesChunkOverlap() throws Exception {
        String space=createSpace();
        Reply upload=upload("/knowledge/spaces/"+space+"/documents",editorToken,"source.txt","text/plain","alpha beta gamma");
        String doc=upload.body().path("documentId").asText(),version=upload.body().path("versionId").asText();ready(version,"alpha beta");
        jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,strategy_version) VALUES(:id,CAST(:v AS uuid),1,:text,'test-v1')")
            .param("id",UUID.randomUUID()).param("v",version).param("text","beta gamma").update();
        String base="/knowledge/documents/"+doc;
        Reply blank=call("PUT",base+"/draft",editorToken,Map.of("title","Original","content",Map.of("type","doc","content",List.of(Map.of("type","paragraph"))),"version",0));
        assertThat(blank.status()).isEqualTo(200);
        assertThat(call("GET",base+"/draft",editorToken,null).body().path("content").toString()).doesNotContain("alpha beta");
        Reply preview=call("GET",base+"/versions/"+version+"/draft-source",editorToken,null);
        assertThat(preview.body().path("content").toString()).contains("alpha beta gamma").doesNotContain("alpha beta beta gamma");
        assertThat(call("PUT",base+"/draft",editorToken,article("Original",null,"stale",0)).status()).isEqualTo(409);
        Reply imported=call("PUT",base+"/draft",editorToken,Map.of("title","Original","content",preview.body().path("content"),"version",blank.body().path("version").asInt()));
        assertThat(imported.status()).isEqualTo(200);
        assertThat(call("GET",base+"/draft",editorToken,null).body().path("content").toString()).contains("alpha beta gamma");
    }
    @Test void reviewTasksAreOnlyVisibleToDedicatedCurrentReviewer() throws Exception {
        String space=createSpace();
        Reply upload=upload("/knowledge/spaces/"+space+"/documents",editorToken,"review.txt","text/plain","review body");
        String doc=upload.body().path("documentId").asText(),version=upload.body().path("versionId").asText();ready(version,"review body");
        String tasks="/knowledge/review-tasks?page=0&size=20";
        Reply requested=call("POST","/knowledge/documents/"+doc+"/versions/"+version+"/review-requests",editorToken,null);
        assertThat(requested.status()).isEqualTo(200);
        assertThat(requested.body().path("versions").get(0).path("hasEligibleReviewer").asBoolean()).isFalse();
        grantReview(space,reviewer,true,true);
        assertThat(call("GET","/knowledge/documents/"+doc,editorToken,null).body().path("versions").get(0).path("hasEligibleReviewer").asBoolean()).isTrue();
        assertThat(call("GET",tasks,editorToken,null).status()).isEqualTo(403);
        assertThat(call("GET",tasks,adminToken,null).status()).isEqualTo(403);
        assertThat(call("GET",tasks,readerToken,null).status()).isEqualTo(403);
        Reply assigned=call("GET",tasks,reviewerToken,null);
        assertThat(assigned.status()).isEqualTo(200);
        assertThat(assigned.body().path("total").asLong()).isEqualTo(1);
        assertThat(assigned.body().path("items").get(0).path("documentId").asText()).isEqualTo(doc);
        grantReview(space,reviewer,true,false);
        assertThat(call("GET","/knowledge/documents/"+doc,editorToken,null).body().path("versions").get(0).path("hasEligibleReviewer").asBoolean()).isFalse();
        assertThat(call("GET",tasks,reviewerToken,null).body().path("total").asLong()).isZero();
        grantReview(space,reviewer,true,true);
        assertThat(call("PUT","/users/"+reviewer+"/roles",adminToken,Map.of("roles",List.of("REQUESTER"))).status()).isEqualTo(204);
        assertThat(call("GET",tasks,reviewerToken,null).status()).isEqualTo(403);
    }
    @Test void categoriesAreSpaceScopedVersionedAndCannotCycleOrDeleteOccupiedBranches() throws Exception {
        String space=createSpace();grant(space,reader,true,false);
        Reply first=call("POST","/knowledge/spaces/"+space+"/categories",editorToken,Map.of("name","Root"));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(201);String id=first.body().path("id").asText();
        assertThat(call("POST","/knowledge/spaces/"+space+"/categories",readerToken,Map.of("name","Forbidden")).status()).isEqualTo(403);
        Reply child=call("POST","/knowledge/spaces/"+space+"/categories",editorToken,Map.of("name","Child","parentId",id));
        assertThat(child.status()).isEqualTo(201);
        assertThat(call("PATCH","/knowledge/categories/"+id,editorToken,Map.of("parentId",child.body().path("id").asText(),"version",first.body().path("version").asInt())).status()).isEqualTo(409);
        assertThat(call("DELETE","/knowledge/categories/"+id,editorToken,Map.of("version",first.body().path("version").asInt())).status()).isEqualTo(409);
        assertThat(call("GET","/knowledge/spaces/"+space+"/categories",adminToken,null).status()).isEqualTo(404);
    }
    @Test void draftConflictAndReviewRequireIndependentCurrentReviewerAndKeepPublishedMetadata() throws Exception {
        String space=createSpace();grant(space,reader,true,false);grantReview(space,reviewer,true,true);
        String key=UUID.randomUUID().toString();var body=article("Original title",null,"original body",null);
        Reply first=callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,body,key);
        assertThat(first.status()).as(first.body().toString()).isEqualTo(201);String doc=first.body().path("id").asText();int revision=first.body().path("version").asInt();
        assertThat(callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,body,key).body()).isEqualTo(first.body());
        assertThat(call("GET","/knowledge/documents/"+doc+"/draft",readerToken,null).status()).isEqualTo(404);
        Reply uploaded=callKey("POST","/knowledge/documents/"+doc+"/draft/versions",editorToken,Map.of("version",revision),UUID.randomUUID().toString());
        assertThat(uploaded.status()).as(uploaded.body().toString()).isEqualTo(202);String version=uploaded.body().path("versionId").asText();ready(version,"original body");
        String base="/knowledge/documents/"+doc+"/versions/"+version;
        assertThat(call("POST",base+"/publish",editorToken,null).status()).isEqualTo(409);
        assertThat(call("POST",base+"/review-requests",editorToken,null).status()).isEqualTo(200);
        assertThat(call("POST",base+"/reviews",editorToken,Map.of("decision","APPROVED")).status()).isEqualTo(403);
        assertThat(call("POST",base+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status()).isEqualTo(200);
        assertThat(call("POST",base+"/publish",editorToken,null).status()).isEqualTo(200);
        assertThat(call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("Private next title",null,"private next body",revision)).status()).isEqualTo(200);
        assertThat(call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("Stale",null,"stale",revision)).status()).isEqualTo(409);
        Reply publicDetail=call("GET","/knowledge/documents/"+doc,readerToken,null);
        assertThat(publicDetail.body().path("title").asText()).isEqualTo("Original title");
        assertThat(publicDetail.body().toString()).doesNotContain("Private next title","private next body");
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_draft WHERE document_id=CAST(:id AS uuid)").param("id",doc).query(Long.class).single()).isEqualTo(1);
    }
    @Test void rejectedVersionCannotPublishAndRevokedReviewerCannotApprove() throws Exception {
        String space=createSpace();grantReview(space,reviewer,true,true);
        Reply upload=upload("/knowledge/spaces/"+space+"/documents",editorToken,"review.txt","text/plain","review body");String doc=upload.body().path("documentId").asText(),version=upload.body().path("versionId").asText();ready(version,"review body");
        String base="/knowledge/documents/"+doc+"/versions/"+version;
        assertThat(call("POST",base+"/review-requests",editorToken,null).status()).isEqualTo(200);
        assertThat(call("POST",base+"/reviews",reviewerToken,Map.of("decision","REJECTED")).status()).isEqualTo(400);
        assertThat(call("POST",base+"/reviews",reviewerToken,Map.of("decision","REJECTED","reason","Needs corrections")).status()).isEqualTo(200);
        assertThat(call("POST",base+"/publish",editorToken,null).status()).isEqualTo(409);
        assertThat(call("POST",base+"/review-requests",editorToken,null).status()).isEqualTo(409);
    }
    @Test void revokedRolesInvalidateAnAlreadyCapturedActorBeforeDraftMutation() throws Exception {
        String space=createSpace();
        var actor=new com.ticknova.shared.Actor(editor,"editor","Editor",Set.of("KB_EDITOR"),false);
        var file=new org.springframework.mock.web.MockMultipartFile("file","guide.txt","text/plain","captured actor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id").param("id",editor).update();
        assertThatThrownBy(()->knowledge.upload(actor,UUID.fromString(space),null,"Denied",file))
            .isInstanceOf(com.ticknova.shared.ApiException.class)
            .satisfies(e->assertThat(((com.ticknova.shared.ApiException)e).status()).isEqualTo(403));
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_document").query(Long.class).single()).isZero();
    }
    @Test void categoryDepthCrossSpaceAndSubtreeFilterAreEnforced() throws Exception {
        String space=createSpace(),root=null,parent=null;
        for(int i=1;i<=5;i++){
            var body=new HashMap<String,Object>();body.put("name","Level "+i);body.put("parentId",parent);
            Reply created=call("POST","/knowledge/spaces/"+space+"/categories",editorToken,body);assertThat(created.status()).isEqualTo(201);
            parent=created.body().path("id").asText();if(root==null)root=parent;
        }
        assertThat(call("POST","/knowledge/spaces/"+space+"/categories",editorToken,Map.of("name","Too deep","parentId",parent)).status()).isEqualTo(409);
        String otherSpace=createSpace();
        assertThat(call("POST","/knowledge/spaces/"+otherSpace+"/categories",editorToken,Map.of("name","Cross space","parentId",parent)).status()).isEqualTo(400);
        Reply draft=callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,article("Categorized",UUID.fromString(parent),"category body",null),UUID.randomUUID().toString());
        assertThat(draft.status()).isEqualTo(201);
        var list=call("GET","/knowledge/spaces/"+space+"/documents?categoryId="+root,editorToken,null);
        assertThat(list.status()).isEqualTo(200);assertThat(list.body().path("total").asInt()).isEqualTo(1);
        assertThat(call("DELETE","/knowledge/categories/"+parent,editorToken,Map.of("version",1)).status()).isEqualTo(409);
    }
    @Test void adminRoleCannotSubstituteForIndependentReviewerRoleOrRevokedSpaceAccess() throws Exception {
        String space=createSpace();grantReview(space,reviewer,true,true);grant(space,admin,true,false);
        Reply upload=upload("/knowledge/spaces/"+space+"/documents",editorToken,"review.txt","text/plain","independent review");
        String doc=upload.body().path("documentId").asText(),version=upload.body().path("versionId").asText();ready(version,"independent review");
        String base="/knowledge/documents/"+doc+"/versions/"+version;
        assertThat(call("POST",base+"/review-requests",editorToken,null).status()).isEqualTo(200);
        assertThat(call("POST",base+"/reviews",adminToken,Map.of("decision","APPROVED")).status()).isEqualTo(403);
        grantReview(space,reviewer,false,false);
        assertThat(call("POST",base+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status()).isEqualTo(404);
        assertThat(call("POST",base+"/publish",editorToken,null).status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT review_state FROM document_version WHERE id=CAST(:id AS uuid)").param("id",version).query(String.class).single()).isEqualTo("PENDING");
    }
    @Test void twoEditorsSavingTheSameDraftRevisionCannotLoseAnUpdate() throws Exception {
        String space=createSpace();grant(space,other,true,true);
        Reply initial=callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,article("Shared",null,"initial",null),UUID.randomUUID().toString());
        String doc=initial.body().path("id").asText();int version=initial.body().path("version").asInt();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("First",null,"first editor",version)));
            var two=pool.submit(()->call("PUT","/knowledge/documents/"+doc+"/draft",otherToken,article("Second",null,"second editor",version)));
            assertThat(List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS).status(),two.get(10,java.util.concurrent.TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200,409);
        }
        Reply current=call("GET","/knowledge/documents/"+doc+"/draft",editorToken,null);
        assertThat(current.body().path("version").asInt()).isEqualTo(version+1);
        assertThat(current.body().path("title").asText()).isIn("First","Second");
    }
    @Test void fileDraftReadDoesNotMutateAndCommitReplayReauthorizesWithoutOverwritingDraft() throws Exception {
        String space=createSpace();grant(space,reader,true,false);
        Reply uploaded=upload("/knowledge/spaces/"+space+"/documents",editorToken,"source.txt","text/plain","source text");
        String doc=uploaded.body().path("documentId").asText(),version=uploaded.body().path("versionId").asText();ready(version,"source text");approve(space,doc,version);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+version+"/publish",editorToken,null).status()).isEqualTo(200);
        Reply draft=call("GET","/knowledge/documents/"+doc+"/draft",editorToken,null);
        assertThat(draft.body().path("version").asInt()).isZero();assertThat(draft.body().path("content").toString()).contains("source text");
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_draft").query(Long.class).single()).isZero();
        Reply saved=call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("Changed",null,"new structured text",0));assertThat(saved.status()).isEqualTo(200);
        String key=UUID.randomUUID().toString();var request=Map.of("version",1);String path="/knowledge/documents/"+doc+"/draft/versions";
        Reply committed=callKey("POST",path,editorToken,request,key);assertThat(committed.status()).isEqualTo(202);
        Reply next=call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("Retained",null,"retained later text",1));assertThat(next.status()).isEqualTo(200);
        assertThat(callKey("POST",path,editorToken,request,key).body()).isEqualTo(committed.body());
        assertThat(callKey("POST",path,editorToken,request,UUID.randomUUID().toString()).status()).isEqualTo(409);
        assertThat(call("GET","/knowledge/documents/"+doc+"/draft",editorToken,null).body().path("content").toString()).contains("retained later text");
        assertThat(call("GET","/knowledge/documents/"+doc,readerToken,null).body().path("versions").size()).isEqualTo(1);
        grant(space,editor,false,false);
        assertThat(callKey("POST",path,editorToken,request,key).status()).isEqualTo(404);
    }
    @Test void richVersionSnapshotRetainsUnderlineAndPrivateRevisionsDoNotLeakToReaders() throws Exception {
        String space=createSpace();grant(space,reader,true,false);
        var initial=article("Rich fidelity",null,"formatted",null);
        Reply created=callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,initial,UUID.randomUUID().toString());
        String doc=created.body().path("id").asText();
        Reply first=callKey("POST","/knowledge/documents/"+doc+"/draft/versions",editorToken,Map.of("version",1),UUID.randomUUID().toString());
        String v1=first.body().path("versionId").asText();ready(v1,"formatted");approve(space,doc,v1);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v1+"/publish",editorToken,null).status()).isEqualTo(200);
        String source="/knowledge/documents/"+doc+"/versions/"+v1+"/content";
        Reply publicContent=call("GET",source,readerToken,null);assertThat(publicContent.status()).isEqualTo(200);
        assertThat(publicContent.body()).isEqualTo(json.valueToTree(initial.get("content")));
        var underlined=article("Rich fidelity",null,"formatted",1);
        underlined.put("content",Map.of("type","doc","content",List.of(Map.of("type","paragraph","content",List.of(Map.of("type","text","text","formatted","marks",List.of(Map.of("type","underline"))))))));
        assertThat(call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,underlined).status()).isEqualTo(200);
        Reply second=callKey("POST","/knowledge/documents/"+doc+"/draft/versions",editorToken,Map.of("version",2),UUID.randomUUID().toString());
        String v2=second.body().path("versionId").asText();assertThat(v2).isNotEqualTo(v1);
        assertThat(jdbc.sql("SELECT count(DISTINCT sha256) FROM document_version WHERE document_id=CAST(:id AS uuid)").param("id",doc).query(Long.class).single()).isEqualTo(1);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+v2+"/content",readerToken,null).status()).isEqualTo(404);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+v2+"/content",editorToken,null).body().toString()).contains("underline");
        assertThat(call("GET",source,readerToken,null).body().toString()).doesNotContain("underline");
        assertThat(call("GET",source,adminToken,null).status()).isEqualTo(404);
    }
    @Test void generatedArticleUploadObeysConfiguredStorageMaximumAndPreservesDraftOnFailure() throws Exception {
        String space=createSpace();
        Reply draft=callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,article("Over custom bound",null,"x".repeat(1025),null),UUID.randomUUID().toString());
        assertThat(draft.status()).isEqualTo(201);String doc=draft.body().path("id").asText();
        Reply rejected=callKey("POST","/knowledge/documents/"+doc+"/draft/versions",editorToken,Map.of("version",1),UUID.randomUUID().toString());
        assertThat(rejected.status()).isEqualTo(413);
        assertThat(jdbc.sql("SELECT count(*) FROM document_version").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_job").query(Long.class).single()).isZero();
        assertThat(call("GET","/knowledge/documents/"+doc+"/draft",editorToken,null).body()).isEqualTo(draft.body());
    }
    @Test void capturedAdminActorCannotManageAnotherEditorsSpaceAfterAdminRoleRevocation() throws Exception {
        String space=createSpace();grant(space,admin,true,false);
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code='KB_EDITOR'").param("id",admin).update();
        var captured=new com.ticknova.shared.Actor(admin,"admin","Admin",Set.of("ADMIN","KB_EDITOR"),false);
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id AND role_id=(SELECT id FROM app_role WHERE code='ADMIN')").param("id",admin).update();
        assertThatThrownBy(()->knowledge.membership(captured,UUID.fromString(space),other,true,true,"Revoked administrator attempt"))
            .isInstanceOf(com.ticknova.shared.ApiException.class)
            .satisfies(e->assertThat(((com.ticknova.shared.ApiException)e).status()).isEqualTo(403));
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_space_member WHERE space_id=CAST(:s AS uuid) AND user_id=:u").param("s",space).param("u",other).query(Long.class).single()).isZero();
    }
    @Test void revertingRichDraftToHistoricalSnapshotKeepsReturnedVersionCurrentAndReviewable() throws Exception {
        String space=createSpace();grant(space,reader,true,false);
        Reply created=callKey("POST","/knowledge/spaces/"+space+"/articles",editorToken,article("Revert",null,"snapshot A",null),UUID.randomUUID().toString());
        String doc=created.body().path("id").asText(),commit="/knowledge/documents/"+doc+"/draft/versions";
        String firstKey=UUID.randomUUID().toString();
        Reply first=callKey("POST",commit,editorToken,Map.of("version",1),firstKey);String v1=first.body().path("versionId").asText();ready(v1,"snapshot A");
        assertThat(call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("Revert",null,"snapshot B",1)).status()).isEqualTo(200);
        Reply second=callKey("POST",commit,editorToken,Map.of("version",2),UUID.randomUUID().toString());String v2=second.body().path("versionId").asText();ready(v2,"snapshot B");approve(space,doc,v2);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v2+"/publish",editorToken,null).status()).isEqualTo(200);
        assertThat(callKey("POST",commit,editorToken,Map.of("version",1),firstKey).body()).isEqualTo(first.body());
        assertThat(call("GET","/knowledge/documents/"+doc,editorToken,null).body().path("latestVersionId").asText()).isEqualTo(v2);
        assertThat(call("PUT","/knowledge/documents/"+doc+"/draft",editorToken,article("Revert",null,"snapshot A",2)).status()).isEqualTo(200);
        Reply reverted=callKey("POST",commit,editorToken,Map.of("version",3),UUID.randomUUID().toString());assertThat(reverted.status()).isEqualTo(202);String returned=reverted.body().path("versionId").asText();
        Reply detail=call("GET","/knowledge/documents/"+doc,editorToken,null);assertThat(detail.body().path("latestVersionId").asText()).isEqualTo(returned);
        assertThat(detail.body().path("publishedVersionId").asText()).isEqualTo(v2);
        approve(space,doc,returned);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+returned+"/publish",editorToken,null).status()).isEqualTo(200);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+returned+"/content",readerToken,null).body().toString()).contains("snapshot A");
        assertThat(jdbc.sql("SELECT count(*) FROM document_version WHERE document_id=CAST(:id AS uuid)").param("id",doc).query(Long.class).single()).isEqualTo(2);
    }
    @Test void revertingFileUploadToHistoricalPendingSnapshotCanCompleteIndependentReview() throws Exception {
        String space=createSpace();grantReview(space,reviewer,true,true);
        Reply first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","file A");String doc=first.body().path("documentId").asText(),v1=first.body().path("versionId").asText();ready(v1,"file A");
        String request="/knowledge/documents/"+doc+"/versions/"+v1+"/review-requests";assertThat(call("POST",request,editorToken,null).status()).isEqualTo(200);
        Reply second=upload("/knowledge/documents/"+doc+"/versions",editorToken,"guide.txt","text/plain","file B");String v2=second.body().path("versionId").asText();assertThat(v2).isNotEqualTo(v1);
        Reply reverted=upload("/knowledge/documents/"+doc+"/versions",editorToken,"guide.txt","text/plain","file A");assertThat(reverted.body().path("versionId").asText()).isEqualTo(v1);
        assertThat(call("GET","/knowledge/documents/"+doc,editorToken,null).body().path("latestVersionId").asText()).isEqualTo(v1);
        assertThat(call("POST",request,editorToken,null).status()).isEqualTo(200);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v1+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status()).isEqualTo(200);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v1+"/publish",editorToken,null).status()).isEqualTo(200);
    }
}
