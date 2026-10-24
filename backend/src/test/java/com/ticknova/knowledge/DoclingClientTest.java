package com.ticknova.knowledge;

import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class DoclingClientTest {
    @TempDir Path temp;
    HttpServer server;
    DoclingClient.Source source;
    @BeforeEach void setup() throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); Path file=temp.resolve("source.bin");Files.writeString(file,"safe text");source=new DoclingClient.Source(UUID.randomUUID(),"a".repeat(64),"guide.txt","text/plain",file);}
    @AfterEach void stop(){server.stop(0);}
    DoclingClient client(Duration timeout){return new DoclingClient("http://127.0.0.1:"+server.getAddress().getPort(),"internal-test-token",new ObjectMapper(),timeout);}
    @Test void sendsFileBytesAndValidatesConversionIdentity() throws Exception {
        server.createContext("/internal/v1/convert",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer internal-test-token");
            assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).contains("safe text","documentVersionId",source.sha256());
            byte[] body=("{\"status\":\"SUCCEEDED\",\"documentVersionId\":\""+source.versionId()+"\",\"sourceSha256\":\""+source.sha256()+"\",\"parserVersion\":\"docling-test\",\"pages\":[{\"pageNo\":1,\"headingPath\":[\"Guide\"],\"text\":\"safe result\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        assertThat(client(Duration.ofSeconds(5)).convert(source).pages().getFirst().text()).isEqualTo("safe result");
    }
    @Test void rejectsMismatchedVersionInsteadOfPersistingUnrelatedOutput() {
        server.createContext("/internal/v1/convert",exchange->{exchange.getRequestBody().readAllBytes();byte[] body="{\"status\":\"SUCCEEDED\",\"documentVersionId\":\"wrong\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
        assertThatThrownBy(()->client(Duration.ofSeconds(5)).convert(source)).isInstanceOfSatisfying(IngestFailure.class,e->{assertThat(e.code()).isEqualTo("PARSER_RESPONSE_INVALID");assertThat(e.retryable()).isFalse();});
    }
    @Test void deadlineAlsoCoversAStalledResponseBody() {
        server.createContext("/internal/v1/convert",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write('{');out.flush();try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});server.start();
        long start=System.nanoTime();assertThatThrownBy(()->client(Duration.ofMillis(200)).convert(source)).isInstanceOfSatisfying(IngestFailure.class,e->assertThat(e.code()).isEqualTo("PARSER_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void requestHeaderTimeoutKeepsItsRetryableTimeoutCode() {
        IngestFailure failure=DoclingClient.transportFailure(new java.util.concurrent.ExecutionException(new HttpTimeoutException("headers timed out")));
        assertThat(failure.code()).isEqualTo("PARSER_TIMEOUT");
        assertThat(failure.retryable()).isTrue();
    }
}
