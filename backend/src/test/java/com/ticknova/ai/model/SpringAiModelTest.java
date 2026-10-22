package com.ticknova.ai.model;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SpringAiModelTest {
    private final JsonMapper json = new JsonMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong responseDelay = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicReference<String> path = new AtomicReference<>(), auth = new AtomicReference<>(), body = new AtomicReference<>();
    private boolean chunkedResponse;
    private boolean gzipResponse;
    private int gzipHeaderFlag;
    private long delayAfterHeaders;
    @AfterEach void stop() { if (server != null) server.stop(0); if(executor != null) executor.shutdownNow(); }

    @Test void constructionRequiresExplicitConfigurationAndDoesNotCallProvider() throws Exception {
        String url = serve(200, "{}", 0);
        var model = new SpringAiModel(url, "test-contract-key", "test-chat", "test-embed");
        assertThat(calls).hasValue(0);
        assertThat(model.profile()).doesNotContain("test-contract-key");
        assertThat(new SpringAiModel(url, "other-key", "test-chat", "test-embed").profile()).isEqualTo(model.profile());
        assertThat(new SpringAiModel(url, "key", "test-chat", "changed-embed").profile()).isNotEqualTo(model.profile());
        assertThatThrownBy(() -> new SpringAiModel(url, "", "", "")).isInstanceOf(AiModelFailure.class);
    }

    @Test void embeddingUsesCompatiblePathAuthModelAndDimensions() throws Exception {
        String url = serve(200, embedding(1024), 0);
        var vectors = new SpringAiModel(url,"test-contract-key","test-chat","test-embed").embed(List.of("密码重置"));
        assertThat(vectors).hasSize(1); assertThat(vectors.getFirst()).hasSize(1024); assertThat(vectors.getFirst()[0]).isEqualTo(1);
        assertThat(path.get()).isEqualTo("/v1/embeddings"); assertThat(auth.get()).isEqualTo("Bearer test-contract-key");
        var request = json.readTree(body.get());
        assertThat(request.path("model").asString()).isEqualTo("test-embed");
        assertThat(request.path("dimensions").asInt()).isEqualTo(1024);
        assertThat(request.path("input").get(0).asString()).isEqualTo("密码重置");
    }

    @Test void chatUsesSpringAiAndJsonDataWithSeparateSystemInstructions() throws Exception {
        String result = "{\"answer\":\"已确认\",\"summary\":\"\",\"serviceId\":null,\"priority\":null,\"tags\":[],\"citationChunkIds\":[]}";
        String url = serve(200, chat(result), 0);
        var output = new SpringAiModel(url,"test-contract-key","test-chat","test-embed").generate(AiModelTest.input("literal {ignore} \" injection", List.of()));
        assertThat(output.answer()).isEqualTo("已确认"); assertThat(path.get()).isEqualTo("/v1/chat/completions");
        var request = json.readTree(body.get());
        assertThat(request.path("model").asString()).isEqualTo("test-chat");
        assertThat(request.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(request.path("messages").get(1).path("content").asString()).contains("literal {ignore}");
        assertThat(request.has("tools")).isFalse();
    }

    @Test void invalidDimensionsNeverFallBackToMock() throws Exception {
        var model = new SpringAiModel(serve(200, embedding(3),0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOfSatisfying(AiModelFailure.class, e -> {
            assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID"); assertThat(e.retryable()).isFalse();
        });
    }

    @Test void upstreamErrorsAreSanitizedAndNotRetriedInsideAdapter() throws Exception {
        var model = new SpringAiModel(serve(503,"{\"error\":{\"message\":\"private provider content\",\"type\":\"server_error\"}}",0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOfSatisfying(AiModelFailure.class, e -> {
            assertThat(e.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE"); assertThat(e.retryable()).isTrue();
            assertThat(e.getCause()).isNull(); assertThat(e.toString()).doesNotContain("private provider content", "test-contract-key");
        });
        assertThat(calls).hasValue(1);
    }

    @Test void delayedTransportHasFiniteDeadlineWithoutRetry() throws Exception {
        String url = serve(200,embedding(1024),0);
        // Initialize the SDK's serializers before timing transport rather than cold JVM class loading.
        new SpringAiModel(url,"test-contract-key","test-chat","test-embed").embed(List.of("warmup"));
        calls.set(0); responseDelay.set(800);
        var model = new SpringAiModel(url,"test-contract-key","test-chat","test-embed",Duration.ofMillis(100));
        long start=System.nanoTime();
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOf(AiModelFailure.class);
        assertThat(calls).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(650));
    }

    @Test void malformedAndOversizedChatOutputsFailClosed() throws Exception {
        var model = new SpringAiModel(serve(200,chat("x".repeat(16001)),0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.generate(AiModelTest.input("question",List.of()))).isInstanceOfSatisfying(AiModelFailure.class,e -> assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID"));
    }

    @Test void malformedJsonChatCannotBecomeAnAnswer() throws Exception {
        var model = new SpringAiModel(serve(200,chat("This is unstructured private content"),0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.generate(AiModelTest.input("question",List.of())))
                .isInstanceOfSatisfying(AiModelFailure.class,e -> assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID"));
    }

    @Test void zeroAndNonfiniteEmbeddingsAreRejectedAtProviderBoundary() throws Exception {
        String response = embedding(1024).replace("\"embedding\":[1,", "\"embedding\":[0,");
        var model = new SpringAiModel(serve(200,response,0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOfSatisfying(AiModelFailure.class,e -> assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID"));
    }

    @Test void responseCountMustMatchBatchRatherThanSilentlyDroppingTexts() throws Exception {
        var model = new SpringAiModel(serve(200,embedding(1024),0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.embed(List.of("first","second"))).isInstanceOfSatisfying(AiModelFailure.class,e -> assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID"));
    }

    @Test void authenticationErrorsAreNonretryableAndSanitized() throws Exception {
        var model = new SpringAiModel(serve(401,"{\"error\":{\"message\":\"private key details\",\"type\":\"authentication_error\"}}",0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOfSatisfying(AiModelFailure.class,e -> {
            assertThat(e.retryable()).isFalse(); assertThat(e.getCause()).isNull();
        });
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"chat,false,false", "chat,true,false", "embedding,false,false", "embedding,true,false", "error,false,false", "error,true,false",
            "chat,false,true", "chat,true,true", "embedding,true,true", "error,true,true"})
    void capsTheHttpEnvelopeBeforeSdkDecodingForEveryResponse(String operation, boolean chunked, boolean gzip) throws Exception {
        String base = switch (operation) {
            case "chat" -> chat("{\"answer\":\"ok\",\"summary\":\"\",\"serviceId\":null,\"priority\":null,\"tags\":[],\"citationChunkIds\":[]}");
            case "embedding" -> embedding(1024);
            default -> "{\"error\":{\"message\":\"private upstream content\",\"type\":\"server_error\"}}";
        };
        // Valid unknown fields must not let a small answer hide an unbounded SDK allocation.
        String oversized = base.substring(0, base.length()-1) + ",\"unused\":\"" + "x".repeat(2 * 1024 * 1024 + 1) + "\"}";
        chunkedResponse = chunked;
        gzipResponse = gzip;
        var model = new SpringAiModel(serve(operation.equals("error") ? 503 : 200,oversized,0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> {
            if(operation.equals("chat")) model.generate(AiModelTest.input("question",List.of()));
            else model.embed(List.of("input"));
        }).isInstanceOfSatisfying(AiModelFailure.class,e -> {
            assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID");
            assertThat(e.retryable()).isFalse();
            assertThat(e.getCause()).isNull();
            assertThat(e.toString()).doesNotContain("private upstream content", "test-contract-key");
        });
        assertThat(calls).hasValue(1);
    }

    @Test void oversizedDeclaredLengthIsRejectedWithoutWaitingForBody() throws Exception {
        delayAfterHeaders = 5000;
        var model = new SpringAiModel(serve(200,"x".repeat(2 * 1024 * 1024 + 1),0),"test-contract-key","test-chat","test-embed",Duration.ofMillis(150));
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOfSatisfying(AiModelFailure.class,e -> {
            // Waiting for a byte would time out with AI_PROVIDER_UNAVAILABLE instead.
            assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID"); assertThat(e.retryable()).isFalse();
        });
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"chat,false", "embedding,true"})
    void ordinaryGzipResponsesRemainUsable(String operation, boolean chunked) throws Exception {
        gzipResponse = true; chunkedResponse = chunked;
        String result = "{\"answer\":\"ok\",\"summary\":\"\",\"serviceId\":null,\"priority\":null,\"tags\":[],\"citationChunkIds\":[]}";
        var model = new SpringAiModel(serve(200,operation.equals("chat") ? chat(result) : embedding(1024),0),"test-contract-key","test-chat","test-embed");
        if(operation.equals("chat")) assertThat(model.generate(AiModelTest.input("question",List.of())).answer()).isEqualTo("ok");
        else assertThat(model.embed(List.of("input")).getFirst()[0]).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"8", "16"})
    void rejectsOversizedRawGzipFilenameAndCommentBeforeDecoding(int flag) throws Exception {
        gzipResponse = true; chunkedResponse = true; gzipHeaderFlag = flag;
        var model = new SpringAiModel(serve(200,embedding(1024),0),"test-contract-key","test-chat","test-embed");
        assertThatThrownBy(() -> model.embed(List.of("input"))).isInstanceOfSatisfying(AiModelFailure.class,e -> {
            assertThat(e.code()).isEqualTo("AI_OUTPUT_INVALID");
            assertThat(e.retryable()).isFalse(); assertThat(e.getCause()).isNull();
        });
        assertThat(calls).hasValue(1);
    }

    private String serve(int status,String response,long delay) throws Exception {
        responseDelay.set(delay);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); executor=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try {
                Thread.sleep(responseDelay.get()); byte[] data=response.getBytes(StandardCharsets.UTF_8);
                if(gzipResponse) {
                    var compressed = new java.io.ByteArrayOutputStream();
                    try(var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(data); }
                    data=compressed.toByteArray();
                    if(gzipHeaderFlag != 0) {
                        var rawHeader = new java.io.ByteArrayOutputStream();
                        data[3] = (byte)gzipHeaderFlag;
                        rawHeader.write(data,0,10);
                        // RFC1952 FNAME/FCOMMENT are NUL-terminated and not part of decoded JSON.
                        rawHeader.write("a".repeat(2 * 1024 * 1024 + 1).getBytes(StandardCharsets.US_ASCII));
                        rawHeader.write(0); rawHeader.write(data,10,data.length-10);
                        data=rawHeader.toByteArray();
                    }
                    exchange.getResponseHeaders().set("Content-Encoding","gzip");
                }
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,chunkedResponse ? 0 : data.length);
                Thread.sleep(delayAfterHeaders); exchange.getResponseBody().write(data);
            }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        }); server.start(); return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
    }
    private String embedding(int dimensions) { return "{\"object\":\"list\",\"model\":\"test-embed\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1"+",0".repeat(dimensions-1)+"]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"; }
    private String chat(String content) { return json.writeValueAsString(java.util.Map.of("id","test-id","object","chat.completion","created",1,"model","test-chat","choices",List.of(java.util.Map.of("index",0,"finish_reason","stop","message",java.util.Map.of("role","assistant","content",content))),"usage",java.util.Map.of("prompt_tokens",1,"completion_tokens",1,"total_tokens",2))); }
}
