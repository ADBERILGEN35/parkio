package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ActuatorPublicSurfaceWebFilterTest {

  @Test
  void blocksInfoForExternalRequestWhenDisabled() {
    var exchange = exchangeFor("/actuator/info", "203.0.113.9");
    var chain = new CapturingChain();

    filter(false).filter(exchange, chain).block();

    assertThat(chain.invoked).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void allowsInfoOnLoopbackWhenDisabled() {
    var exchange = exchangeFor("/actuator/info", "127.0.0.1");
    var chain = new CapturingChain();

    filter(false).filter(exchange, chain).block();

    assertThat(chain.invoked).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void allowsInfoForExternalRequestWhenEnabled() {
    var exchange = exchangeFor("/actuator/info", "203.0.113.9");
    var chain = new CapturingChain();

    filter(true).filter(exchange, chain).block();

    assertThat(chain.invoked).isTrue();
  }

  @Test
  void blocksEnvAndConfigpropsForExternalRequestWhenDisabled() {
    for (String path : new String[] {"/actuator/env", "/actuator/configprops"}) {
      var exchange = exchangeFor(path, "172.18.0.5");
      var chain = new CapturingChain();

      filter(false).filter(exchange, chain).block();

      assertThat(chain.invoked).isFalse();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  void spoofedForwardedHeadersDoNotBypassExternalBlock() {
    for (String path : new String[] {"/actuator/info", "/actuator/env", "/actuator/configprops"}) {
      var request = MockServerHttpRequest.get(path)
          .remoteAddress(new InetSocketAddress("203.0.113.9", 443))
          .header("X-Forwarded-For", "127.0.0.1")
          .header("X-Real-IP", "127.0.0.1")
          .header("Forwarded", "for=127.0.0.1")
          .build();
      var exchange = MockServerWebExchange.from(request);
      var chain = new CapturingChain();

      filter(false).filter(exchange, chain).block();

      assertThat(chain.invoked).isFalse();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  void healthRemainsReachableForExternalRequestWhenDisabled() {
    var exchange = exchangeFor("/actuator/health", "203.0.113.9");
    var chain = new CapturingChain();

    filter(false).filter(exchange, chain).block();

    assertThat(chain.invoked).isTrue();
  }

  private static ActuatorPublicSurfaceWebFilter filter(boolean actuatorInfoEnabled) {
    GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
    properties.setActuatorInfoEnabled(actuatorInfoEnabled);
    return new ActuatorPublicSurfaceWebFilter(properties);
  }

  private static MockServerWebExchange exchangeFor(String path, String remoteHost) {
    return MockServerWebExchange.from(MockServerHttpRequest.get(path)
        .remoteAddress(new InetSocketAddress(remoteHost, 443))
        .build());
  }

  private static final class CapturingChain implements WebFilterChain {
    private boolean invoked;

    @Override
    public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
      invoked = true;
      return Mono.empty();
    }
  }
}
