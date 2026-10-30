package com.parkio.gateway.infrastructure.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The waitlist RestClient reads and writes JSON with Jackson 2, like the rest of the gateway (#515). */
class WaitlistRestClientConfigTest {

    @Test
    void builderUsesTheJackson2Mapper() {
        RestClient.Builder builder = new WaitlistRestClientConfig().waitlistRestClientBuilder(new ObjectMapper());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.test/emails"))
                .andRespond(withSuccess("{\"id\":\"e-1\"}", MediaType.APPLICATION_JSON));

        // Only a Jackson 2 converter can produce a com.fasterxml.jackson JsonNode; Jackson 3 fails here.
        JsonNode body = builder.build().get().uri("https://provider.test/emails").retrieve().body(JsonNode.class);

        assertThat(body).isNotNull();
        assertThat(body.get("id").asText()).isEqualTo("e-1");
        server.verify();
    }
}
