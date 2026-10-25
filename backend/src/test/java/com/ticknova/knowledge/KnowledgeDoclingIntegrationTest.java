package com.ticknova.knowledge;

import com.ticknova.shared.Actor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"ticknova.knowledge.worker-enabled=false","ticknova.knowledge.upload-root=/tmp/ticknova-test-real-docling"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_DOCLING_INTEGRATION",matches="true")
class KnowledgeDoclingIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeWorker worker;
    @Autowired KnowledgeAuthoringService authoring;
    @Autowired tools.jackson.databind.ObjectMapper json;
    Actor editor;
    Actor reviewer;
    UUID space;
    @BeforeEach void setup() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");jdbc.sql("TRUNCATE app_user CASCADE").update();
        UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'editor','Editor','unused',false)").param("id",id).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code IN ('KB_EDITOR','ADMIN')").param("id",id).update();
        editor=new Actor(id,"editor","Editor",Set.of("KB_EDITOR","ADMIN"),false);space=knowledge.createSpace(editor,"Real Docling").id();
        knowledge.membership(editor,space,id,true,true,"Explicit editor fixture");
        UUID reviewerId=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'reviewer','Reviewer','unused',false)").param("id",reviewerId).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code='KB_REVIEWER'").param("id",reviewerId).update();
        reviewer=new Actor(reviewerId,"reviewer","Reviewer",Set.of("KB_REVIEWER"),false);
        knowledge.membership(editor,space,reviewerId,true,false,true,"Independent review fixture");
    }
    @Test void markdownTravelsThroughRealHttpParserAndPublication(){ingest("guide.md","text/markdown","# Guide\n\nSafe knowledge for local tests.".getBytes(StandardCharsets.UTF_8));}
    @Test void txtTravelsThroughRealHttpParserAndPublication(){ingest("guide.txt","text/plain","Safe knowledge for local tests.".getBytes(StandardCharsets.UTF_8));}
    @Test void pdfTravelsThroughRealHttpParserAndPreservesPage(){var result=ingest("guide.pdf","application/pdf",pdf());assertThat(knowledge.chunks(editor,result.documentId(),result.versionId())).allMatch(c->Integer.valueOf(1).equals(c.pageNo()));}
    @Test void docxTravelsThroughRealHttpParserAndPublication() throws Exception {ingest("guide.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",docx());}
    @Test void realMalformedPdfFailureDoesNotWithdrawPreviousPublication() {
        var initial=ingest("guide.txt","text/plain","Prior published knowledge".getBytes(StandardCharsets.UTF_8));
        var failed=knowledge.upload(editor,null,initial.documentId(),null,new MockMultipartFile("file","broken.pdf","application/pdf","%PDF-broken file".getBytes(StandardCharsets.US_ASCII)));
        worker.runOnce();var detail=knowledge.detail(editor,initial.documentId());
        assertThat(detail.publishedVersionId()).isEqualTo(initial.versionId());
        var version=detail.versions().getFirst();assertThat(version.ingestState()).isEqualTo("FAILED");assertThat(version.errorCode()).isEqualTo("INVALID_DOCUMENT");assertThat(version.attemptCount()).isEqualTo(1);
        assertThat(knowledge.job(editor,failed.jobId()).status()).isEqualTo("FAILED");assertThat(knowledge.chunks(editor,initial.documentId(),initial.versionId())).isNotEmpty();
    }
    @Test void generatedInlineCodeRetainsLiteralThroughPrivateStorageAndRealDocling(){
        String literal="a < b && C:\\tmp\\x_y* `quoted`";
        var content=json.valueToTree(Map.of("type","doc","content",List.of(Map.of("type","paragraph","content",List.of(Map.of("type","text","text",literal,"marks",List.of(Map.of("type","code"))))))));
        var draft=authoring.create(editor,space,"Literal guide",null,content,UUID.randomUUID().toString());
        var upload=authoring.commit(editor,draft.id(),draft.version(),UUID.randomUUID().toString());
        assertThat(worker.runOnce()).isTrue();assertThat(knowledge.detail(editor,draft.id()).latestIngestState()).isEqualTo("READY");
        String extracted=knowledge.chunks(editor,draft.id(),upload.versionId()).stream().map(KnowledgeDtos.Chunk::content).reduce("",(a,b)->a+"\n"+b);
        assertThat(extracted).contains(literal).doesNotContain("&lt;","&amp;","x\\_y");
    }
    KnowledgeDtos.Upload ingest(String name,String mime,byte[] bytes) {
        var result=knowledge.upload(editor,space,null,"Guide",new MockMultipartFile("file",name,mime,bytes));assertThat(worker.runOnce()).isTrue();
        var detail=knowledge.detail(editor,result.documentId());assertThat(detail.latestIngestState()).as(detail.toString()).isEqualTo("READY");
        assertThat(knowledge.job(editor,result.jobId()).status()).isEqualTo("COMPLETED");
        knowledge.requestReview(editor,result.documentId(),result.versionId());
        knowledge.review(reviewer,result.documentId(),result.versionId(),"APPROVED",null);
        assertThat(knowledge.publish(editor,result.documentId(),result.versionId()).publishedVersionId()).isEqualTo(result.versionId());
        assertThat(knowledge.chunks(editor,result.documentId(),result.versionId())).isNotEmpty().allMatch(c->!c.content().isBlank());
        return result;
    }
    static byte[] pdf() {
        String content="BT /F1 12 Tf 72 720 Td (Safe knowledge for local tests.) Tj ET";
        List<String> objects=List.of("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>","<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>","<< /Length "+content.length()+" >>\nstream\n"+content+"\nendstream");
        var data=new ByteArrayOutputStream();data.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<objects.size();i++){offsets.add(data.size());data.writeBytes(((i+1)+" 0 obj\n"+objects.get(i)+"\nendobj\n").getBytes(StandardCharsets.US_ASCII));}
        int xref=data.size();data.writeBytes(("xref\n0 6\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for(int offset:offsets)data.writeBytes(String.format(Locale.ROOT,"%010d 00000 n \n",offset).getBytes(StandardCharsets.US_ASCII));
        data.writeBytes(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));return data.toByteArray();
    }
    static byte[] docx() throws IOException {
        var data=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(data)) {
            entry(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(zip,"word/document.xml","<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Safe knowledge for local tests.</w:t></w:r></w:p><w:sectPr/></w:body></w:document>");
        }return data.toByteArray();
    }
    static void entry(ZipOutputStream zip,String name,String value) throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
