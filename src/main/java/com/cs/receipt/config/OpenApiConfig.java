package com.cs.receipt.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI receiptOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Receipt Split API")
                .version("v1")
                .description("API for creating receipts, assigning item costs to participants, "
                        + "calculating balances, and managing the receipt lifecycle.")
                .license(new License().name("Private")));
    }
}
