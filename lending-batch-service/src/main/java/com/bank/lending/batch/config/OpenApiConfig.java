package com.bank.lending.batch.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and the HTTP Basic login for the Swagger UI ("Authorize" button) of the batch admin API. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI batchOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Lending batch service - admin API")
                        .description("Job configuration, manual triggers, executions, and the end-of-day run (/api/v1/batch-admin/**). "
                                + "Log in with the batch admin user.")
                        .version("0.1.0"))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
