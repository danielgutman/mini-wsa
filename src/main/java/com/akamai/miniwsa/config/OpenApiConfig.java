package com.akamai.miniwsa.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document-level metadata for the generated OpenAPI spec ({@code /v3/api-docs}) and Swagger UI
 * ({@code /swagger-ui.html}). springdoc derives the endpoints, schemas, and validation constraints
 * from the controllers and DTOs; this only sets the title, description, and version.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI miniWsaOpenApi() {
        return new OpenAPI()
                // Relative server so Swagger UI's "Try it out" targets whatever origin served the
                // page (e.g. localhost:8080, or the nginx edge) instead of a hard-coded host/port.
                .servers(List.of(new Server().url("/")))
                .info(new Info()
                        .title("Mini WSA API")
                        .description("Web Security Analytics pipeline — ingest security events, enrich them "
                                + "(attack type + a 0-100 threat score), and query summary, time-series, and "
                                + "sample analytics.")
                        .version("1.1.0"));
    }
}
