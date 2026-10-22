package com.ticknova.ai.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Deterministic hash/extractive demonstration. No semantic or real-model quality claim. */
public final class MockAiModel implements AiModel {
    private final ChatClient client;

    public MockAiModel() {
        ChatModel chatModel = prompt -> {
            var input = AiText.JSON.readValue(prompt.getInstructions().getLast().getText(), Input.class);
            String content = AiText.JSON.writeValueAsString(extract(input));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        };
        client = ChatClient.create(chatModel);
    }

    @Override public String profile() { return "mock:hash-v1:1024"; }
    @Override public String mode() { return "mock"; }

    @Override public List<float[]> embed(List<String> texts) {
        AiText.embeddingInput(texts);
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) {
            float[] vector = new float[1024];
            // Query terms are capped, but indexed chunk tails must remain represented.
            Set<String> allTerms = new LinkedHashSet<>();
            for (int offset = 0; offset < text.length(); offset += 64)
                allTerms.addAll(AiText.terms(text.substring(offset, Math.min(text.length(), offset + 128))));
            var terms = List.copyOf(allTerms);
            // Punctuation-only input still has a valid deterministic representation.
            if (terms.isEmpty()) terms = List.of(text);
            for (String term : terms) {
                byte[] hash = digest(term);
                int index = ((hash[0] & 255) << 8 | (hash[1] & 255)) % 1024;
                vector[index] += (hash[2] & 1) == 0 ? 1 : -1;
            }
            double squared = 0; for (float value : vector) squared += value * value;
            if (squared == 0) vector[0] = 1;
            else for (int i = 0; i < vector.length; i++) vector[i] /= (float) Math.sqrt(squared);
            AiText.vectorLiteral(vector); vectors.add(vector);
        }
        return List.copyOf(vectors);
    }

    @Override public Output generate(Input input) {
        String data = AiText.inputJson(input);
        return AiText.output(client.prompt().system(SpringAiModel.SYSTEM).user(u -> u.text(data)).call().content());
    }

    private static Output extract(Input input) {
        String ticket = String.join("\n", AiText.safe(input.ticketTitle()), AiText.safe(input.ticketDescription()), String.join("\n", input.publicReplies())).strip();
        if ("SUMMARY".equals(input.kind())) return new Output("", AiText.cut(ticket, 2000), null, null, List.of(), List.of());
        if ("CLASSIFICATION".equals(input.kind())) {
            var terms = new HashSet<>(AiText.terms(ticket));
            var service = input.services().stream().filter(s -> AiText.terms(s.name()).stream().anyMatch(terms::contains)).findFirst().orElse(null);
            return new Output("", "", service == null ? null : service.id(), "NORMAL", List.of(), List.of());
        }
        Set<String> query = new HashSet<>(AiText.terms(AiText.safe(input.question()) + " " + ticket));
        record Evidence(Source source, String sentence, long score) {}
        List<Evidence> evidence = new ArrayList<>();
        for (Source source : input.sources()) {
            for (String sentence : source.content().split("(?<=[。！？.!?\\n])")) {
                String lower = sentence.toLowerCase(Locale.ROOT);
                if (lower.contains("忽略") || lower.contains("泄露") || lower.contains("system") || lower.contains("ignore")
                        || lower.contains("secret") || lower.contains("指令") || lower.contains("角色")) continue;
                long matches = AiText.terms(sentence).stream().filter(query::contains).count();
                if (matches > 0) evidence.add(new Evidence(source, sentence.strip(), matches));
            }
        }
        evidence.sort(Comparator.comparingLong(Evidence::score).reversed().thenComparing(e -> e.source().chunkId()));
        if (evidence.isEmpty()) return new Output("无法从当前授权知识库确认，请补充资料或提交工单。", "", null, null, List.of(), List.of());
        var best = evidence.getFirst();
        return new Output(AiText.cut(best.sentence(), 2000), "", null, null, List.of(), List.of(best.source().chunkId()));
    }

    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
