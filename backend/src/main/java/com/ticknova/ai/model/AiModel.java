package com.ticknova.ai.model;

import java.util.List;
import java.util.UUID;

/** Provider boundary. Inputs contain only previously authorized public information. */
public interface AiModel {
    String profile();
    String mode();
    List<float[]> embed(List<String> texts);
    Output generate(Input input);

    record Source(UUID chunkId, String content) {}
    record Input(String kind, String question, String ticketTitle, String ticketDescription,
                 List<String> publicReplies, List<Source> sources, List<ServiceOption> services) {}
    record ServiceOption(UUID id, String name) {}
    record Output(String answer, String summary, UUID serviceId, String priority,
                  List<String> tags, List<UUID> citationChunkIds) {}
}
