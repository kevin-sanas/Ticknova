package com.ticknova.ai;

import com.ticknova.ai.model.AiModel;
import java.util.*;

/** Deterministic context allocation; complete legal title/description always have priority. */
final class AiContextBudget {
    static final int GENERATION_CHARS=60000;
    private AiContextBudget() {}

    record Prepared(AiModel.Input input,List<AiRetriever.Candidate> sources) {}

    static Prepared prepare(String kind,String question,String title,String description,List<String> replies,
            List<AiRetriever.Candidate> candidates,List<AiModel.ServiceOption> catalog) {
        int remaining=GENERATION_CHARS-length(question)-length(title)-length(description);
        if(remaining<0)throw new IllegalArgumentException("Required context exceeds the input contract");
        List<AiRetriever.Candidate> sources=new ArrayList<>();
        if(kind.equals("ANSWER")||kind.equals("REPLY")) {
            int sourceBudget=Math.min(24000,remaining);
            for(var candidate:candidates) {
                if(sources.size()>=6||candidate.content().length()>sourceBudget)break;
                sources.add(candidate);sourceBudget-=candidate.content().length();remaining-=candidate.content().length();
            }
        }
        List<AiModel.ServiceOption> services=new ArrayList<>();
        if(kind.equals("CLASSIFICATION")) {
            for(var service:catalog) {
                int cost=service.name().length()+36;
                if(services.size()>=200||cost>remaining)break;
                services.add(service);remaining-=cost;
            }
        }
        List<String> publicReplies=new ArrayList<>();
        for(String reply:replies) {
            if(publicReplies.size()>=100||reply.length()>remaining)break;
            publicReplies.add(reply);remaining-=reply.length();
        }
        var input=new AiModel.Input(kind,question,title,description,List.copyOf(publicReplies),
            sources.stream().map(c->new AiModel.Source(c.citation().chunkId(),c.content())).toList(),List.copyOf(services));
        return new Prepared(input,List.copyOf(sources));
    }

    /** Title plus equally sized description prefix/suffix, <=4000 UTF16 units including separators. */
    static String retrievalQuery(String title,String description) {
        String complete=title+"\n"+description;
        if(complete.length()<=4000)return complete;
        int available=4000-title.length()-2;
        int first=available/2,last=available-first;
        int prefixEnd=first;
        if(prefixEnd>0&&Character.isHighSurrogate(description.charAt(prefixEnd-1)))prefixEnd--;
        int suffixStart=description.length()-last;
        if(suffixStart<description.length()&&Character.isLowSurrogate(description.charAt(suffixStart)))suffixStart++;
        return title+"\n"+description.substring(0,prefixEnd)+"\n"+description.substring(suffixStart);
    }

    private static int length(String text){return text==null?0:text.length();}
}
