package com.ticknova.ai;

import com.ticknova.ai.model.AiModel;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.ticknova.ai.AiDtos.*;
import static com.ticknova.ticket.TicketDtos.COMMENT_MAX_LENGTH;

@Component
public class AiResultValidator {
    public AiResult validate(UUID id,String kind,String mode,String profile,AiModel.Output output,List<AiRetriever.Candidate> candidates,List<AiModel.ServiceOption> services){
        return validate(id,kind,mode,profile,output,candidates,services,List.of());
    }
    public AiResult validate(UUID id,String kind,String mode,String profile,AiModel.Output output,List<AiRetriever.Candidate> candidates,List<AiModel.ServiceOption> services,List<String> publicTicketContext){
        if(output==null)return refusal(id,kind,mode,profile);
        List<UUID> ids=output.citationChunkIds();List<String> tags=output.tags();
        if(ids==null||ids.size()>6||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())return refusal(id,kind,mode,profile);
        Map<UUID,Citation> allowed=new HashMap<>();for(var c:candidates)allowed.put(c.citation().chunkId(),c.citation());
        if(!allowed.keySet().containsAll(ids))return refusal(id,kind,mode,profile);
        if(length(output.answer())+length(output.summary())>16000)return refusal(id,kind,mode,profile);
        if((kind.equals("REPLY")&&length(output.answer())>COMMENT_MAX_LENGTH)
            ||(kind.equals("SUMMARY")&&length(output.summary())>COMMENT_MAX_LENGTH))return refusal(id,kind,mode,profile);
        boolean answer=kind.equals("ANSWER")||kind.equals("REPLY");
        if(answer&&(ids.isEmpty()||blank(output.answer())))return refusal(id,kind,mode,profile);
        if(answer){
            List<String> evidence=new ArrayList<>();for(var candidate:candidates)if(ids.contains(candidate.citation().chunkId()))evidence.add(candidate.content());
            if(kind.equals("REPLY"))evidence.addAll(publicTicketContext);
            if(!AiEvidenceGuard.supported(output.answer(),evidence))return refusal(id,kind,mode,profile);
        }
        if(kind.equals("SUMMARY")&&blank(output.summary()))return refusal(id,kind,mode,profile);
        if(kind.equals("CLASSIFICATION")){
            if(output.priority()==null||!Set.of("LOW","NORMAL","HIGH","URGENT").contains(output.priority())||tags==null||tags.size()>10||tags.stream().anyMatch(t->t==null||t.isBlank()||t.length()>32)||new HashSet<>(tags).size()!=tags.size())return refusal(id,kind,mode,profile);
            if(output.serviceId()!=null&&services.stream().noneMatch(s->s.id().equals(output.serviceId())))return refusal(id,kind,mode,profile);
        }
        return new AiResult(id,kind,mode,profile,"ANSWERED",answer?output.answer():null,kind.equals("SUMMARY")?output.summary():null,
            kind.equals("CLASSIFICATION")?output.serviceId():null,kind.equals("CLASSIFICATION")?output.priority():null,kind.equals("CLASSIFICATION")?List.copyOf(tags):List.of(),ids.stream().map(allowed::get).toList(),true,null);
    }
    public AiResult refusal(UUID id,String kind,String mode,String profile){return new AiResult(id,kind,mode,profile,"INSUFFICIENT_EVIDENCE",null,null,null,null,List.of(),List.of(),true,null);}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static int length(String s){return s==null?0:s.length();}
}
