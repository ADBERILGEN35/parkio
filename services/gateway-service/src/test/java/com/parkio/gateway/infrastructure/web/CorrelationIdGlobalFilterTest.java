package com.parkio.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.shared.GatewayHeaders;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdGlobalFilterTest {

    /** The documented bound on accepted client correlation ids. */
    private static final int MAX_LENGTH = 128;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesCorrelationIdWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/users/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        String forwarded = chain.captured().getRequest().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID);
        assertThat(forwarded).matches(UUID_PATTERN);
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID)).isEqualTo(forwarded);
        assertThat(exchange.getAttributes().get(GatewayHeaders.CORRELATION_ID_ATTRIBUTE)).isEqualTo(forwarded);
    }

    @Test
    void forwardsClientSuppliedCorrelationId() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(GatewayHeaders.CORRELATION_ID, "client-correlation-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.captured().getRequest().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID))
                .isEqualTo("client-correlation-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID))
                .isEqualTo("client-correlation-123");
    }

    static Stream<Arguments> acceptedIds() {
        return Stream.of(
                Arguments.of("a UUID, as the web and mobile clients send", "0f8fad5b-d9cb-469f-a165-70867728950e", "0f8fad5b-d9cb-469f-a165-70867728950e"),
                Arguments.of("every accepted character class", "Az09._:-", "Az09._:-"),
                Arguments.of("a single character", "x", "x"),
                Arguments.of("exactly the maximum length", "a".repeat(MAX_LENGTH), "a".repeat(MAX_LENGTH)),
                Arguments.of("surrounding whitespace, trimmed", "  trace-42\t", "trace-42"));
    }

    @ParameterizedTest(name = "keeps {0}")
    @MethodSource("acceptedIds")
    void keepsAcceptedIds(String description, String supplied, String expected) {
        Outcome outcome = run(supplied);

        assertThat(outcome.forwarded()).containsExactly(expected);
        assertThat(outcome.echoed()).isEqualTo(expected);
        assertThat(outcome.attribute()).isEqualTo(expected);
    }

    static Stream<Arguments> rejectedIds() {
        return Stream.of(
                Arguments.of("CR/LF (log or header injection)", "abc\r\nX-Injected: 1"),
                Arguments.of("a bare line feed", "abc\ndef"),
                Arguments.of("a NUL byte", "abc\u0000def"),
                Arguments.of("an inner space", "abc def"),
                Arguments.of("non-ASCII letters", "korelasyon-çıktı"),
                Arguments.of("markup", "<script>alert(1)</script>"),
                Arguments.of("quotes", "\"abc\""),
                Arguments.of("one character over the maximum", "a".repeat(MAX_LENGTH + 1)),
                Arguments.of("an oversized value", "a".repeat(8192)),
                Arguments.of("whitespace only", " \t "),
                Arguments.of("an empty value", ""));
    }

    @ParameterizedTest(name = "replaces {0} with a generated id")
    @MethodSource("rejectedIds")
    void replacesRejectedIdsWithAGeneratedOne(String description, String supplied) {
        Outcome outcome = run(supplied);

        assertThat(outcome.forwarded()).hasSize(1);
        String generated = outcome.forwarded().get(0);
        assertThat(generated).matches(UUID_PATTERN);
        assertThat(outcome.echoed()).isEqualTo(generated);
        assertThat(outcome.attribute()).isEqualTo(generated);
    }

    @Test
    void forwardsOnlyTheCheckedValueWhenTheClientRepeatsTheHeader() {
        Outcome first = run("first-id", "abc\r\nX-Injected: 1");
        assertThat(first.forwarded()).containsExactly("first-id");
        assertThat(first.echoed()).isEqualTo("first-id");

        Outcome invalidFirst = run("abc\r\nX-Injected: 1", "second-id");
        assertThat(invalidFirst.forwarded()).hasSize(1);
        assertThat(invalidFirst.forwarded().get(0)).matches(UUID_PATTERN);
        assertThat(invalidFirst.echoed()).isEqualTo(invalidFirst.forwarded().get(0));
    }

    @Test
    void generatesADifferentIdForEachRejectedRequest() {
        String a = run("bad id").echoed();
        String b = run("bad id").echoed();

        assertThat(UUID.fromString(a)).isNotEqualTo(UUID.fromString(b));
    }

    private Outcome run(String... suppliedValues) {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(GatewayHeaders.CORRELATION_ID, suppliedValues)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        return new Outcome(
                chain.captured().getRequest().getHeaders().get(GatewayHeaders.CORRELATION_ID),
                exchange.getResponse().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID),
                (String) exchange.getAttributes().get(GatewayHeaders.CORRELATION_ID_ATTRIBUTE));
    }

    private record Outcome(List<String> forwarded, String echoed, String attribute) {
    }

    private static final class CapturingChain implements GatewayFilterChain {

        private ServerWebExchange captured;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return Mono.empty();
        }

        ServerWebExchange captured() {
            return captured;
        }
    }
}
