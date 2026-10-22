package com.ticknova.knowledge;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class KnowledgeChunkerTest {
    @Test void boundedChineseChunksHaveOverlapAndProvenance() {
        String text="甲乙丙丁戊己庚辛壬癸".repeat(140);
        var chunks=new KnowledgeChunker().split(new DoclingClient.Parsed("docling-test",List.of(new DoclingClient.Page(7,List.of("运维","网关"),text))));
        assertThat(chunks).hasSize(3); assertThat(chunks.getFirst().content()).hasSize(600);
        assertThat(chunks.get(1).content()).startsWith(text.substring(520,600));
        assertThat(chunks).allMatch(c->c.pageNo()==7 && c.headingPath().equals("运维 / 网关"));
        assertThat(chunks.getLast().content()).endsWith(text.substring(text.length()-80));
    }
    @Test void removesUnsafeControlCharactersAndRejectsNoExtractableText() {
        var chunker=new KnowledgeChunker();
        var pieces=chunker.split(new DoclingClient.Parsed("test",List.of(new DoclingClient.Page(null,List.of(),"hello\0world\nplain text"))));
        assertThat(pieces.getFirst().content()).isEqualTo("helloworld\nplain text");
        assertThatThrownBy(()->chunker.split(new DoclingClient.Parsed("test",List.of(new DoclingClient.Page(null,List.of()," \n\t"))))).isInstanceOf(IngestFailure.class);
    }
}
