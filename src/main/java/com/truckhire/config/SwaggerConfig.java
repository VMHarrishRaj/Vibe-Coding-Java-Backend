package com.truckhire.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI Configuration.
 *
 * WHAT THIS DOES:
 * - Auto-generates API documentation from your controllers
 * - Provides an interactive UI to test endpoints
 * - Accessible at: http://localhost:8080/api/v1/swagger-ui.html
 *
 * WHY IT MATTERS:
 * - Mobile developers can see all endpoints, request/response formats
 * - No need to maintain a separate API doc — it's always in sync with code
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TruckRental API")
                        .description("Truck Rental Marketplace Platform API")
                        .version("v0.1.0")
                        .contact(new Contact()
                                .name("TruckRental Team")))
                // Add "Authorize" button in Swagger UI for JWT
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .bearerFormat("JWT")
                                        .scheme("bearer")));
    }
}
