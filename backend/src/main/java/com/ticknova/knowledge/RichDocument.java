package com.ticknova.knowledge;

import com.ticknova.shared.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Closed, bounded structured schema; Markdown output never contains raw HTML. */
@Component
public class RichDocument {
    private final ObjectMapper json;
    public RichDocument(ObjectMapper json){this.json=json;}
    private static final Set<String> BLOCK=Set.of("paragraph","heading","bulletList","orderedList","blockquote","codeBlock","horizontalRule");
    private static final Set<String> MARKS=Set.of("bold","italic","strike","underline","code","link");
    private static class Bounds {int nodes,text;}
    public JsonNode normalize(JsonNode value) {
        if(value==null||json.writeValueAsBytes(value).length>1024*1024)throw invalid();
        if(!"doc".equals(value.path("type").asText()))throw invalid();
        return node(value,"root",0,new Bounds());
    }
    private ObjectNode node(JsonNode n,String parent,int depth,Bounds bound) {
        if(!n.isObject()||depth>32||++bound.nodes>20000)throw invalid();
        only(n,Set.of("type","attrs","content","text","marks"));
        String type=n.path("type").asText("");
        boolean permitted=switch(parent){
            case "root"->type.equals("doc");
            case "doc","blockquote"->BLOCK.contains(type);
            case "listItem"->BLOCK.contains(type);
            case "bulletList","orderedList"->type.equals("listItem");
            case "paragraph","heading"->type.equals("text")||type.equals("hardBreak");
            case "codeBlock"->type.equals("text");default->false;};
        if(!permitted)throw invalid();
        ObjectNode out=json.createObjectNode().put("type",type);
        JsonNode attrs=n.get("attrs");
        if(attrs!=null&&!attrs.isObject())throw invalid();
        if(type.equals("heading")) {
            only(attrs,Set.of("level"));JsonNode level=attrs==null?null:attrs.get("level");
            if(level==null||!level.isIntegralNumber()||level.asInt()<1||level.asInt()>3)throw invalid();out.putObject("attrs").put("level",level.asInt());
        } else if(type.equals("orderedList")) {
            only(attrs,Set.of("start"));int start=attrs==null?1:attrs.path("start").asInt(1);
            if(start<1||start>1000000||(attrs!=null&&attrs.has("start")&&!attrs.get("start").isIntegralNumber()))throw invalid();out.putObject("attrs").put("start",start);
        } else if(type.equals("codeBlock")) {
            only(attrs,Set.of("language"));String language=attrs==null?"":attrs.path("language").asText("");
            if(!language.matches("[A-Za-z0-9_+.-]{0,40}"))throw invalid();if(!language.isEmpty())out.putObject("attrs").put("language",language);
        } else only(attrs,Set.of());
        if(type.equals("text")) {
            if(!n.path("text").isTextual()||n.has("content")||n.path("text").asText().indexOf('\0')>=0)throw invalid();
            String text=n.path("text").asText();bound.text+=text.length();if(bound.text>100000)throw invalid();out.put("text",text);
            JsonNode marks=n.get("marks");
            if(marks!=null){if(!marks.isArray()||parent.equals("codeBlock")&&!marks.isEmpty())throw invalid();Set<String> seen=new HashSet<>();
                ArrayNode normalized=out.putArray("marks");
                for(JsonNode mark:marks){if(++bound.nodes>20000)throw invalid();only(mark,Set.of("type","attrs"));String mt=mark.path("type").asText();if(!MARKS.contains(mt)||!seen.add(mt))throw invalid();var m=json.createObjectNode().put("type",mt);
                    if(mt.equals("link")){only(mark.get("attrs"),Set.of("href","target","rel","class"));String href=mark.path("attrs").path("href").asText("");safeHref(href);m.putObject("attrs").put("href",href);}
                    else only(mark.get("attrs"),Set.of());normalized.add(m);
                }
            }
        } else {
            if(n.has("text")||n.has("marks"))throw invalid();JsonNode content=n.get("content");
            if(type.equals("hardBreak")||type.equals("horizontalRule")){if(content!=null&&!content.isEmpty())throw invalid();}
            else {if(content!=null&&!content.isArray())throw invalid();ArrayNode children=out.putArray("content");if(content!=null)for(JsonNode child:content)children.add(node(child,type,depth+1,bound));
                if((type.equals("bulletList")||type.equals("orderedList")||type.equals("listItem"))&&children.isEmpty())throw invalid();
                if(type.equals("listItem")&&!children.get(0).path("type").asText().equals("paragraph"))throw invalid();}
        }
        return out;
    }
    private static void only(JsonNode node,Set<String> keys){if(node==null)return;if(!node.isObject())throw invalid();for(String key:node.propertyNames())if(!keys.contains(key))throw invalid();}
    private static void safeHref(String href){
        if(href.isBlank()||href.length()>2048||href.chars().anyMatch(c->c<=32||c==127||c=='<'||c=='>'||c=='"'||c=='\\'))throw invalid();
        try {URI u=URI.create(href);String scheme=Objects.requireNonNullElse(u.getScheme(),"").toLowerCase(Locale.ROOT);if(!Set.of("http","https","mailto").contains(scheme)||((scheme.equals("http")||scheme.equals("https"))&&(u.getHost()==null||u.getUserInfo()!=null))||scheme.equals("mailto")&&u.getSchemeSpecificPart().isBlank())throw invalid();}catch(IllegalArgumentException e){throw invalid();}
    }
    private static ApiException invalid(){return new ApiException(400,"INVALID_RICH_DOCUMENT","文章结构、链接或内容大小不符合安全要求");}
    public JsonNode paragraphs(List<String> paragraphs){var doc=json.createObjectNode().put("type","doc");var blocks=doc.putArray("content");for(String text:paragraphs){var p=blocks.addObject().put("type","paragraph");if(!text.isEmpty())p.putArray("content").addObject().put("type","text").put("text",text);}return normalize(doc);}
    public String markdown(JsonNode normalized){return render(normalized).strip()+"\n";}
    private String render(JsonNode n){
        String type=n.path("type").asText();StringBuilder b=new StringBuilder();for(JsonNode child:n.path("content"))b.append(render(child));String body=b.toString();
        return switch(type){
            case "doc"->body;case "paragraph"->body+"\n\n";case "heading"->"#".repeat(n.path("attrs").path("level").asInt())+" "+body+"\n\n";
            case "hardBreak"->"  \n";case "horizontalRule"->"\n---\n\n";
            case "text"->renderText(n);
            case "blockquote"->body.stripTrailing().lines().map(line->"> "+line).reduce("",(a,c)->a+c+"\n")+"\n";
            case "bulletList","orderedList"->{StringBuilder list=new StringBuilder();int number=n.path("attrs").path("start").asInt(1);for(JsonNode item:n.path("content")){String prefix=type.equals("bulletList")?"- ":number+++". ";String[] lines=render(item).stripTrailing().split("\n",-1);list.append(prefix).append(lines[0]).append('\n');for(int i=1;i<lines.length;i++)list.append(" ".repeat(prefix.length())).append(lines[i]).append('\n');}yield list+"\n";}
            case "listItem"->body;
            case "codeBlock"->{StringBuilder raw=new StringBuilder();for(JsonNode c:n.path("content"))raw.append(c.path("text").asText());String text=raw.toString();String fence="```";while(text.contains(fence))fence+="`";yield fence+n.path("attrs").path("language").asText("")+"\n"+text+"\n"+fence+"\n\n";}
            default->throw invalid();};
    }
    private String renderText(JsonNode n){
        var marks=new HashMap<String,JsonNode>();for(JsonNode mark:n.path("marks"))marks.put(mark.path("type").asText(),mark);
        String raw=n.path("text").asText();String text=marks.containsKey("code")?codeSpan(raw):escape(raw);
        // A code span contains literal source; all compatible formatting wraps it in a stable order.
        if(marks.containsKey("bold"))text="**"+text+"**";
        if(marks.containsKey("italic"))text="*"+text+"*";
        if(marks.containsKey("strike"))text="~~"+text+"~~";
        if(marks.containsKey("link"))text="["+text+"]("+marks.get("link").path("attrs").path("href").asText().replace("(","%28").replace(")","%29")+")";
        return text;
    }
    private String codeSpan(String raw){
        String fence="`";while(raw.contains(fence))fence+="`";
        boolean padding=raw.startsWith("`")||raw.endsWith("`")||(raw.startsWith(" ")&&raw.endsWith(" ")&&!raw.chars().allMatch(c->c==' '));
        return fence+(padding?" ":"")+raw+(padding?" ":"")+fence;
    }
    private String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\\","\\\\").replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("[","\\[").replace("]","\\]").replace("~","\\~").replace("#","\\#");}
}
