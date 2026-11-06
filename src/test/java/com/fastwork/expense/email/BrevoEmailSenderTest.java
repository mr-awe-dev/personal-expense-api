package com.fastwork.expense.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit test pengirim email Brevo: URL, header api-key, payload JSON, dan pemetaan kegagalan
 * (HTTP maupun jaringan) menjadi 503. Lapisan diuji via MockRestServiceServer tanpa jaringan.
 */
class BrevoEmailSenderTest {

    private static final String BASE_URL = BrevoEmailSender.API_URL;

    private static final EmailMessage MESSAGE =
            new EmailMessage("user@contoh.com", "Kode pemulihan password", "Kode 12345678");

    private record Harness(MockRestServiceServer server, BrevoEmailSender sender) {}

    private static Harness harness(String apiKey, String from, String fromName) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(builder, apiKey, from, fromName);
        return new Harness(server, sender);
    }

    private static JsonNode brevoBodyOf(Object request) {
        try {
            return new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Body perantara tidak bisa dibaca sebagai JSON", ex);
        }
    }

    @Test
    void sendPostsJsonWithApiKeyRecipientAndText() throws Exception {
        Harness harness = harness("kunci-rahasia", "saya@app.com", "Nama Aplikasi");
        harness.server()
                .expect(requestTo(BASE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "kunci-rahasia"))
                .andExpect(header("content-type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(
                        request -> {
                            JsonNode body = brevoBodyOf(request);
                            assertThat(body.get("sender").get("email").asText())
                                    .isEqualTo("saya@app.com");
                            assertThat(body.get("sender").get("name").asText())
                                    .isEqualTo("Nama Aplikasi");
                            assertThat(body.get("to").get(0).get("email").asText())
                                    .isEqualTo("user@contoh.com");
                            assertThat(body.get("subject").asText())
                                    .isEqualTo("Kode pemulihan password");
                            assertThat(body.get("textContent").asText()).isEqualTo("Kode 12345678");
                        })
                .andRespond(withSuccess());
        harness.sender().send(MESSAGE);
        harness.server().verify();
    }

    @Test
    void fromNameFallbackUsesFromAddress() throws Exception {
        Harness harness = harness("kunci", "saya@app.com", "   ");
        harness.server()
                .expect(requestTo(BASE_URL))
                .andExpect(
                        request -> {
                            JsonNode body = brevoBodyOf(request);
                            assertThat(body.get("sender").get("name").asText())
                                    .isEqualTo("saya@app.com");
                        })
                .andRespond(withSuccess());
        harness.sender().send(MESSAGE);
        harness.server().verify();
    }

    @Test
    void providerRejectionMapsTo503() {
        Harness harness = harness("kunci", "saya@app.com", "");
        harness.server().expect(requestTo(BASE_URL)).andRespond(withUnauthorizedRequest());
        assertThatThrownBy(() -> harness.sender().send(MESSAGE))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        harness.server().verify();
    }

    @Test
    void networkFailureMapsTo503() {
        Harness harness = harness("kunci", "saya@app.com", "");
        harness.server()
                .expect(requestTo(BASE_URL))
                .andRespond(withException(new java.io.IOException("contoh putung jaringan")));
        assertThatThrownBy(() -> harness.sender().send(MESSAGE))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        harness.server().verify();
    }
}
