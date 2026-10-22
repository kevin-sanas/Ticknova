package com.ticknova.knowledge;

import com.ticknova.shared.ApiException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RichDocumentTest {
    final ObjectMapper json=new ObjectMapper();
    final RichDocument rich=new RichDocument(json);
    @Test void canonicalMarkdownEscapesHtmlAndPreservesSafeFormatting() {
        var doc=json.readTree("""
          {"type":"doc","content":[{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Safe title"}]},{"type":"paragraph","content":[{"type":"text","text":"<script> & hello","marks":[{"type":"bold"}]}]}]}
          """);
        assertThat(rich.markdown(rich.normalize(doc))).contains("## Safe title","**&lt;script&gt; &amp; hello**").doesNotContain("<script>");
    }
    @Test void rejectsExecutableLinksUnknownAttributesAndInvalidStructure() {
        for(String value:new String[]{
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"onclick\":\"run()\"}}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"bad\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:run()\"}}]}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"src\":\"https://example.com/x\"}}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"text\",\"text\":\"invalid block\"}]}"})
            assertThatThrownBy(()->rich.normalize(json.readTree(value))).isInstanceOf(ApiException.class);
    }
    @Test void boundedTextAndDepthPreventOversizedDrafts() {
        var doc=json.createObjectNode().put("type","doc");
        doc.putArray("content").addObject().put("type","paragraph").putArray("content").addObject().put("type","text").put("text","x".repeat(100001));
        assertThatThrownBy(()->rich.normalize(doc)).isInstanceOf(ApiException.class);
        String nested="{\"type\":\"paragraph\"}";
        for(int i=0;i<34;i++)nested="{\"type\":\"blockquote\",\"content\":["+nested+"]}";
        String value="{\"type\":\"doc\",\"content\":["+nested+"]}";
        assertThatThrownBy(()->rich.normalize(json.readTree(value))).isInstanceOf(ApiException.class);
    }
    @Test void rejectsWideTreesAndNormalizesTiptapLinksToClosedSafeAttributes() {
        var wide=json.createObjectNode().put("type","doc");var blocks=wide.putArray("content");
        for(int i=0;i<20000;i++)blocks.addObject().put("type","paragraph");
        assertThatThrownBy(()->rich.normalize(wide)).isInstanceOf(ApiException.class);
        var safe=json.readTree("""
            {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Guide","marks":[{"type":"link","attrs":{"href":"https://example.com/guide","target":"_blank","rel":"noopener noreferrer","class":null}}]}]}]}
            """);
        var normalized=rich.normalize(safe);
        assertThat(normalized.toString()).contains("https://example.com/guide").doesNotContain("target","rel","class");
        assertThat(rich.markdown(normalized)).contains("[Guide](https://example.com/guide)");
    }
    @Test void inlineCodePreservesRawLiteralsAndUsesSafeBacktickDelimiters() {
        String literal="a < b && C:\\tmp\\x_y* [value]";
        assertThat(codeMarkdown(literal,"code")).isEqualTo("`"+literal+"`\n");
        assertThat(codeMarkdown("`quoted`","code")).isEqualTo("`` `quoted` ``\n");
        assertThat(codeMarkdown("a``b","code")).isEqualTo("```a``b```\n");
        assertThat(codeMarkdown(" leading and trailing ","code")).isEqualTo("`  leading and trailing  `\n");
    }
    @Test void codeIsInnermostWithCanonicalOuterMarksRegardlessOfInputOrder() {
        String literal="a < b && C:\\tmp\\x_y*";
        String expected="**`"+literal+"`**\n";
        assertThat(codeMarkdown(literal,"bold","code")).isEqualTo(expected);
        assertThat(codeMarkdown(literal,"code","bold")).isEqualTo(expected);
    }
    String codeMarkdown(String text,String... marks){
        var doc=json.createObjectNode().put("type","doc");var node=doc.putArray("content").addObject().put("type","paragraph").putArray("content").addObject().put("type","text").put("text",text);
        var markNodes=node.putArray("marks");for(String mark:marks)markNodes.addObject().put("type",mark);
        return rich.markdown(rich.normalize(doc));
    }
}
