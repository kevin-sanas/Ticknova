package com.ticknova.ai.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Shared bounded lexical representation; this is deliberately not a semantic tokenizer. */
public final class AiText {
    static final int DIMENSIONS = 1024;
    static final int MAX_INPUT_CHARS = 64_000;
    static final int MAX_OUTPUT_CHARS = 16_000;
    static final JsonMapper JSON = new JsonMapper();
    private AiText() {}

    public static List<String> terms(String text) {
        if (text == null || text.isEmpty()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        String bounded = text.substring(0, Math.min(MAX_INPUT_CHARS, text.length())).toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        int previousHan = -1;
        for (int offset = 0; offset < bounded.length() && terms.size() < 256;) {
            int cp = bounded.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                addWord(terms, word);
                if (previousHan >= 0) terms.add(new String(Character.toChars(previousHan)) + new String(Character.toChars(cp)));
                previousHan = cp;
            } else {
                previousHan = -1;
                if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
                    if (word.length() < 64) word.appendCodePoint(cp);
                } else addWord(terms, word);
            }
        }
        if (terms.size() < 256) addWord(terms, word);
        return List.copyOf(terms);
    }

    private static void addWord(Set<String> terms, StringBuilder word) {
        if (!word.isEmpty()) { terms.add(word.toString()); word.setLength(0); }
    }

    public static String vectorLiteral(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) throw invalid();
        boolean nonzero = false;
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw invalid();
            nonzero |= vector[i] != 0;
            if (i > 0) literal.append(',');
            literal.append(Float.toString(vector[i]));
        }
        if (!nonzero) throw invalid();
        return literal.append(']').toString();
    }

    static List<String> embeddingInput(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.size() > 32) throw inputInvalid();
        int total = 0;
        for (String text : texts) {
            // Conservative code-unit bound, not a provider token-count guarantee.
            if (text == null || text.isBlank() || text.length() > 8192) throw inputInvalid();
            total += text.length();
            if (total > MAX_INPUT_CHARS) throw inputInvalid();
        }
        return List.copyOf(texts);
    }

    static String inputJson(AiModel.Input input) {
        if (input == null || !Set.of("ANSWER", "REPLY", "SUMMARY", "CLASSIFICATION").contains(input.kind())
                || input.sources() == null || input.publicReplies() == null || input.services() == null
                || input.sources().size() > 6 || input.publicReplies().size() > 100 || input.services().size() > 200) throw inputInvalid();
        long total = length(input.question()) + length(input.ticketTitle()) + length(input.ticketDescription());
        for (String reply : input.publicReplies()) { if (reply == null) throw inputInvalid(); total += reply.length(); }
        for (var source : input.sources()) {
            if (source == null || source.chunkId() == null || source.content() == null) throw inputInvalid();
            total += source.content().length();
        }
        for (var service : input.services()) {
            if (service == null || service.id() == null || service.name() == null) throw inputInvalid();
            total += service.name().length();
        }
        if (total > MAX_INPUT_CHARS) throw inputInvalid();
        try { return JSON.writeValueAsString(input); } catch (RuntimeException e) { throw inputInvalid(); }
    }

    static AiModel.Output output(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_OUTPUT_CHARS) throw invalid();
        try {
            var output = JSON.readValue(content, AiModel.Output.class);
            if (output == null || output.tags() == null || output.citationChunkIds() == null
                    || output.tags().size() > 10 || output.citationChunkIds().size() > 6
                    || output.tags().stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 32)
                    || output.citationChunkIds().stream().anyMatch(java.util.Objects::isNull)
                    || length(output.answer()) + length(output.summary()) > MAX_OUTPUT_CHARS) throw invalid();
            return new AiModel.Output(output.answer(), output.summary(), output.serviceId(), output.priority(),
                    List.copyOf(output.tags()), List.copyOf(output.citationChunkIds()));
        } catch (RuntimeException e) { throw invalid(); }
    }

    static String safe(String text) { return text == null ? "" : text; }
    static String cut(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }
    private static long length(String text) { return text == null ? 0 : text.length(); }
    static AiModelFailure invalid() { return new AiModelFailure("AI_OUTPUT_INVALID", false); }
    static AiModelFailure inputInvalid() { return new AiModelFailure("AI_INPUT_INVALID", false); }
}
