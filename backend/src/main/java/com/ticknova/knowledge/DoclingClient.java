package com.ticknova.knowledge;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class DoclingClient {
    public record Source(UUID versionId,String sha256,String fileName,String mimeType,Path path) {}
    public record Page(Integer pageNo,List<String> headingPath,String text) {}
    public record Parsed(String parserVersion,List<Page> pages) {}
    private final URI endpoint;
    private final String token;
    private final ObjectMapper json;
    private final Duration timeout;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    @Autowired
    public DoclingClient(@Value("${ticknova.knowledge.docling-base-url:http://docling:8000}") String base,@Value("${ticknova.knowledge.docling-token:}") String token,ObjectMapper json) {this(base,token,json,Duration.ofSeconds(95));}
    DoclingClient(String base,String token,ObjectMapper json,Duration timeout) {
        this.endpoint=URI.create(base.replaceAll("/+$","")+"/internal/v1/convert");this.token=token;this.json=json;this.timeout=timeout;
        if(!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null)throw new IllegalArgumentException("Invalid parser endpoint");
    }
    public Parsed convert(Source source) {
        if(token.isBlank())throw new IngestFailure("PARSER_NOT_CONFIGURED",false);
        String boundary="ticknova-"+UUID.randomUUID();
        String prefix=field(boundary,"documentVersionId",source.versionId().toString())+field(boundary,"sha256",source.sha256())+field(boundary,"originalFileName",source.fileName())+
            "--"+boundary+"\r\nContent-Disposition: form-data; name=\"sourceFile\"; filename=\"source\"\r\nContent-Type: "+source.mimeType()+"\r\n\r\n";
        try {
            var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix,StandardCharsets.UTF_8),HttpRequest.BodyPublishers.ofFile(source.path()),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
            var request=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
            var pending=http.sendAsync(request,ignored->new BoundedBody());
            HttpResponse<byte[]> response;
            try {response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);} catch(TimeoutException e){pending.cancel(true);throw new IngestFailure("PARSER_TIMEOUT",true);}
            catch(ExecutionException e){if(e.getCause() instanceof IngestFailure failure)throw failure;throw new IngestFailure("PARSER_UNAVAILABLE",true);}
            byte[] bytes=response.body();
            if(response.statusCode()!=200) {
                if(response.statusCode()==401||response.statusCode()==403)throw new IngestFailure("PARSER_AUTH_FAILED",false);
                String code="PARSER_REJECTED";
                try {String reported=json.readTree(bytes).path("errorCode").asText(); if(Set.of("INVALID_REQUEST","INVALID_FILENAME","UNSUPPORTED_FORMAT","MIME_MISMATCH","INVALID_UTF8","INVALID_DOCUMENT","HASH_MISMATCH","FILE_TOO_LARGE","PDF_PAGE_LIMIT","CONVERSION_FAILED","CONVERSION_TIMEOUT","NO_EXTRACTABLE_TEXT","OUTPUT_TOO_LARGE","PARSER_BUSY","SERVICE_UNAVAILABLE").contains(reported))code=reported;}catch(Exception ignored){}
                throw new IngestFailure(code,response.statusCode()>=500||response.statusCode()==429||code.equals("CONVERSION_TIMEOUT"));
            }
            JsonNode root=json.readTree(bytes);
            if(!root.path("status").asText().equals("SUCCEEDED")||!root.path("documentVersionId").asText().equals(source.versionId().toString())||!root.path("sourceSha256").asText().equals(source.sha256()))throw new IngestFailure("PARSER_RESPONSE_INVALID",false);
            String version=root.path("parserVersion").asText(); if(version.isBlank()||version.length()>160)throw new IngestFailure("PARSER_RESPONSE_INVALID",false);
            List<Page> pages=new ArrayList<>();
            for(JsonNode p:root.path("pages")) {
                Integer number=p.path("pageNo").isNull()||p.path("pageNo").isMissingNode()?null:p.path("pageNo").asInt();
                if(number!=null&&(number<1||number>100))throw new IngestFailure("PARSER_RESPONSE_INVALID",false);
                List<String> headings=new ArrayList<>(); for(JsonNode h:p.path("headingPath")) {if(!h.isTextual())throw new IngestFailure("PARSER_RESPONSE_INVALID",false);headings.add(h.asText());}
                pages.add(new Page(number,List.copyOf(headings),p.path("text").asText()));
                if(pages.size()>10000)throw new IngestFailure("PARSER_OUTPUT_TOO_LARGE",false);
            }
            if(pages.isEmpty()&&!root.path("markdown").asText().isBlank())pages.add(new Page(null,List.of(),root.path("markdown").asText()));
            return new Parsed(version,List.copyOf(pages));
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IngestFailure("PARSER_INTERRUPTED",true);}
        catch(IngestFailure e){throw e;}
        catch(IOException e){throw new IngestFailure("PARSER_UNAVAILABLE",true);}
        catch(RuntimeException e){throw new IngestFailure("PARSER_RESPONSE_INVALID",false);}
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer b:buffers) {
                if(bytes.size()+b.remaining()>8*1024*1024){subscription.cancel();result.completeExceptionally(new IngestFailure("PARSER_OUTPUT_TOO_LARGE",false));return;}
                byte[] block=new byte[b.remaining()];b.get(block);bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private static String field(String boundary,String name,String value){return "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n";}
}
