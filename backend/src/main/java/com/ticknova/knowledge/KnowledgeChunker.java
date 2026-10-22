package com.ticknova.knowledge;

import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeChunker {
    public static final String STRATEGY="unicode-approx-v1-600-80";
    private static final Pattern TOKENS=Pattern.compile("[A-Za-z0-9_]{1,4}|[^\\s]",Pattern.UNICODE_CHARACTER_CLASS);
    public record Part(int index,Integer pageNo,String headingPath,String content) {}
    public List<Part> split(DoclingClient.Parsed document) {
        List<Part> result=new ArrayList<>();long total=0;
        for(var page:document.pages()) {
            String text=clean(page.text()); total+=text.length(); if(total>4*1024*1024)throw new IngestFailure("PARSER_OUTPUT_TOO_LARGE",false);
            if(text.isBlank())continue;
            String heading=clean(String.join(" / ",page.headingPath())); if(heading.length()>2000)heading=heading.substring(0,2000);
            List<Integer> starts=new ArrayList<>();var match=TOKENS.matcher(text);while(match.find())starts.add(match.start());
            int start=0;
            while(start<starts.size()) {
                int end=Math.min(start+600,starts.size());
                // Prefer a nearby paragraph boundary while keeping full chunks in the 500–700 token range.
                if(end<starts.size())for(int i=end;i>start+500;i--) {int position=starts.get(i);if(position>0&&text.charAt(position-1)=='\n'){end=i;break;}}
                int first=starts.get(start),last=end==starts.size()?text.length():starts.get(end);
                String content=text.substring(first,last).strip();
                if(!content.isBlank())result.add(new Part(result.size(),page.pageNo(),heading,content));
                if(result.size()>10000)throw new IngestFailure("PARSER_OUTPUT_TOO_LARGE",false);
                if(end==starts.size())break; start=end-80;
            }
        }
        if(result.isEmpty())throw new IngestFailure("NO_EXTRACTABLE_TEXT",false); return List.copyOf(result);
    }
    static String clean(String value) { if(value==null)return "";return value.replaceAll("[\\p{Cc}&&[^\\n\\r\\t]]","").replace("\r\n","\n").replace('\r','\n'); }
}
