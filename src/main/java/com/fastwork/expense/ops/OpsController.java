package com.fastwork.expense.ops;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Ops", description = "Endpoint operasional")
public class OpsController {

    @GetMapping
    @Operation(summary = "Health check publik")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
