package com.ticknova.shared;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean OpenAPI ticknovaOpenApi() {
        return new OpenAPI().info(new Info().title("Ticknova API").version("0.1.0")
                .description("G0–G2 单组织工单闭环。会话30分钟；登录后的首次改密会话只允许 /me、改密和登出。"))
            .components(new Components().addSecuritySchemes("bearerSession",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
            .addSecurityItem(new SecurityRequirement().addList("bearerSession"));
    }
    @Bean OpenApiCustomizer contractDetails() {
        return api->{
            var login=api.getPaths().get("/api/v1/auth/login");
            if(login!=null&&login.getPost()!=null)login.getPost().setSecurity(List.of());
            api.getPaths().values().forEach(path->path.readOperations().forEach(operation->{
                if(operation.getParameters()!=null)operation.getParameters().stream()
                    .filter(p->p.getName().equals("Idempotency-Key")).forEach(p->p.setRequired(true));
            }));
        };
    }
}
