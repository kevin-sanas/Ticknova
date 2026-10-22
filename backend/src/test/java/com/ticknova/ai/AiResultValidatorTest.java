package com.ticknova.ai;

import com.ticknova.ai.model.AiModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import jakarta.validation.Validation;
import com.ticknova.ticket.TicketDtos;
import static org.assertj.core.api.Assertions.*;

class AiResultValidatorTest {
    final AiResultValidator validator=new AiResultValidator();
    final UUID chunk=UUID.randomUUID(),suggestion=UUID.randomUUID(),service=UUID.randomUUID();
    final List<AiRetriever.Candidate> candidates=List.of(new AiRetriever.Candidate(new AiDtos.Citation(UUID.randomUUID(),UUID.randomUUID(),1,"指南",null,chunk,"片段"),"片段",1));
    @Test void answerRequiresActualRecalledCitations(){
        assertThat(validate("ANSWER",new AiModel.Output("text",null,null,null,List.of(),List.of())).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(validate("ANSWER",new AiModel.Output("text",null,null,null,List.of(),List.of(UUID.randomUUID()))).answer()).isNull();
        assertThat(validate("ANSWER",new AiModel.Output("text",null,null,null,List.of(),List.of(chunk))).citations()).hasSize(1);
    }
    @Test void summaryCanUseTicketWithoutKnowledgeButCannotInventCitation(){
        assertThat(validate("SUMMARY",new AiModel.Output(null,"summary",null,null,List.of(),List.of())).outcome()).isEqualTo("ANSWERED");
        assertThat(validate("SUMMARY",new AiModel.Output(null,"summary",null,null,List.of(),List.of(UUID.randomUUID()))).summary()).isNull();
    }
    @Test void classificationRejectsUnknownServicePriorityOrTags(){
        assertThat(validate("CLASSIFICATION",new AiModel.Output(null,null,UUID.randomUUID(),"NORMAL",List.of(),List.of())).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(validate("CLASSIFICATION",new AiModel.Output(null,null,service,"ROOT",List.of(),List.of())).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(validate("CLASSIFICATION",new AiModel.Output(null,null,service,"NORMAL",List.of("x".repeat(33)),List.of())).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(validate("CLASSIFICATION",new AiModel.Output(null,null,service,"NORMAL",List.of("安全"),List.of())).outcome()).isEqualTo("ANSWERED");
    }
    @Test void outputBudgetAndDuplicateCitationAreRejected(){
        assertThat(validate("ANSWER",new AiModel.Output("x".repeat(16001),null,null,null,List.of(),List.of(chunk))).answer()).isNull();
        assertThat(validate("ANSWER",new AiModel.Output("text",null,null,null,List.of(),List.of(chunk,chunk))).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    @Test void unrelatedOutputFieldsNeverReachTheRequestedKind(){
        var summary=validate("SUMMARY",new AiModel.Output("untrusted-extra","summary",service,"NORMAL",List.of("tag"),List.of()));
        assertThat(summary.answer()).isNull();assertThat(summary.serviceId()).isNull();assertThat(summary.priority()).isNull();assertThat(summary.tags()).isEmpty();
    }
    @ParameterizedTest
    @CsvSource({"REPLY,10000,true","REPLY,10001,false","REPLY,11000,false","SUMMARY,10000,true","SUMMARY,10001,false","SUMMARY,11000,false"})
    void ticketSuggestionsFitThePublicCommentDestination(String kind,int length,boolean accepted){
        String text="文".repeat(length);
        var output=new AiModel.Output(kind.equals("REPLY")?text:null,kind.equals("SUMMARY")?text:null,null,null,List.of(),kind.equals("REPLY")?List.of(chunk):List.of());
        var result=validate(kind,output);
        assertThat(result.outcome()).isEqualTo(accepted?"ANSWERED":"INSUFFICIENT_EVIDENCE");
        if(accepted) assertThat(kind.equals("REPLY")?result.answer():result.summary()).isEqualTo(text);
        else { assertThat(result.answer()).isNull();assertThat(result.summary()).isNull();assertThat(result.citations()).isEmpty(); }
    }
    @ParameterizedTest
    @CsvSource({"16000,true","16001,false"})
    void knowledgeAnswersKeepTheirExistingBudget(int length,boolean accepted){
        var result=validate("ANSWER",new AiModel.Output("知".repeat(length),null,null,null,List.of(),List.of(chunk)));
        assertThat(result.outcome()).isEqualTo(accepted?"ANSWERED":"INSUFFICIENT_EVIDENCE");
        assertThat(result.answer()).isEqualTo(accepted?"知".repeat(length):null);
    }
    @ParameterizedTest
    @CsvSource({"10000,true","10001,false"})
    void publicCommentValidationKeepsTheSameDestinationBoundary(int length,boolean accepted){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var violations=factory.getValidator().validate(new TicketDtos.CommentRequest("PUBLIC_REPLY","文".repeat(length)));
            assertThat(violations.isEmpty()).isEqualTo(accepted);
        }
    }
    private AiDtos.AiResult validate(String kind,AiModel.Output output){return validator.validate(suggestion,kind,"mock","test:1024",output,candidates,List.of(new AiModel.ServiceOption(service,"服务")));}
}
