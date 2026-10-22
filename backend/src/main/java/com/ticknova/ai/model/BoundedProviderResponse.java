package com.ticknova.ai.model;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.GzipSource;
import okio.Okio;
import okio.Source;

/** Bounds every provider HTTP envelope before SDK buffering/JSON decoding. */
final class BoundedProviderResponse implements Interceptor {
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    @Override public Response intercept(Chain chain) throws IOException {
        // Explicit negotiation prevents OkHttp from hiding the compressed Content-Length.
        // Both raw bytes (including gzip headers) and decoded bytes get independent caps.
        Response response = chain.proceed(chain.request().newBuilder().header("Accept-Encoding", "gzip").build());
        ResponseBody body = response.body();
        if (body == null) return response;
        if (body.contentLength() > MAX_BYTES) throw reject(chain, response);
        String encoding = response.header("Content-Encoding", "identity");
        boolean gzip = "gzip".equalsIgnoreCase(encoding);
        if (!gzip && !"identity".equalsIgnoreCase(encoding)) throw reject(chain, response);
        // Limit before GzipSource can buffer a NUL-terminated FNAME/FCOMMENT header.
        BufferedSource raw = bounded(body.source(), chain, response);
        Source decoded = gzip ? new GzipSource(raw) : raw;
        BufferedSource counted = bounded(decoded, chain, response);
        // Read the bounded envelope before returning it to the SDK: its error-response
        // decoder otherwise swallows stream IO failures and loses the limit marker.
        Buffer envelope = new Buffer();
        try (counted) { counted.readAll(envelope); }
        catch (IOException | RuntimeException error) {
            chain.call().cancel(); response.close(); throw error;
        }
        long size = envelope.size();
        var boundedBody = new ResponseBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return size; }
            @Override public BufferedSource source() { return envelope; }
        };
        var builder = response.newBuilder().body(boundedBody);
        if (gzip) builder.removeHeader("Content-Encoding").removeHeader("Content-Length");
        return builder.build();
    }

    private static BufferedSource bounded(Source source, Chain chain, Response response) {
        return Okio.buffer(new ForwardingSource(source) {
            private long consumed;
            @Override public long read(Buffer sink, long byteCount) throws IOException {
                // Read at most one byte beyond the budget; never pass it to the consumer.
                long read = super.read(sink, Math.min(byteCount, MAX_BYTES - consumed + 1));
                if (read > 0) consumed += read;
                if (consumed > MAX_BYTES) throw reject(chain, response);
                return read;
            }
        });
    }

    private static InvalidEnvelope reject(Chain chain, Response response) {
        // Cancel before close, avoiding a potentially large discard/read for pooled reuse.
        chain.call().cancel();
        response.close();
        return new InvalidEnvelope();
    }

    /** Marker survives SDK IO wrappers; no URL, credential, input or response is retained. */
    static final class InvalidEnvelope extends IOException {
        InvalidEnvelope() { super("AI_OUTPUT_INVALID"); }
    }
}
