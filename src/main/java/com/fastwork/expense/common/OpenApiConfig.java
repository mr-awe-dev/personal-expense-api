package com.fastwork.expense.common;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Personal Expense Tracker API",
                        version = "v1",
                        description = "API Personal Expense Tracker (spesifikasi v5)"))
@SecurityScheme(
        name = "csrf",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "XSRF-TOKEN",
        description = "Cookie CSRF; nilai dikirim balik di header X-XSRF-TOKEN")
@SecurityRequirement(name = "csrf")
public class OpenApiConfig {}
