package com.fastwork.expense.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("openapi")
class OpenApiExportTest {

    private static final Path OUTPUT = Paths.get("..", "docs", "openapi.json").toAbsolutePath();

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;

    @Autowired private ObjectMapper objectMapper;

    @Test
    void exportsOpenApiContract() throws Exception {
        ResponseEntity<String> response =
                rest.getForEntity("http://127.0.0.1:" + port + "/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"");

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, pretty(normalize(response.getBody())));
    }

    /**
     * springdoc menulis blok {@code servers} berisi port acak server uji, sehingga kontrak akan
     * selalu terlihat berubah. Blok tersebut dibuang agar hasil ekspor deterministik.
     */
    private String normalize(String json) throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(json);
        root.remove("servers");
        return objectMapper.writeValueAsString(root);
    }

    private String pretty(String json) throws Exception {
        return objectMapper
                        .writer()
                        .with(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(objectMapper.readTree(json))
                + System.lineSeparator();
    }
}
