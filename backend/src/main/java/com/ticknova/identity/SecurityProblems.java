package com.ticknova.identity;

import com.ticknova.shared.ApiErrors;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityProblems {
    private final ObjectMapper json;
    public SecurityProblems(ObjectMapper json) { this.json=json; }
    public void write(HttpServletRequest request,HttpServletResponse response,int status,String code,String detail) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getOutputStream(),Map.of("type","about:blank","title",HttpStatus.valueOf(status).getReasonPhrase(),"status",status,"code",code,"detail",detail,"traceId",ApiErrors.traceId(request)));
    }
}
