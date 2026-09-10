package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class MunicipalSourceFailureClassifierTest {

    @Test
    void classifiesReadTimeoutThroughResourceAccessWrapper() {
        Throwable wrapped = new ResourceAccessException(
                "I/O error", new SocketTimeoutException("Read timed out"));
        assertThat(MunicipalSourceFailureClassifier.classify(wrapped))
                .isEqualTo(MunicipalSourceFailureCategory.READ_TIMEOUT);
        assertThat(MunicipalSourceFailureClassifier.wireValue(wrapped)).isEqualTo("read_timeout");
    }

    @Test
    void classifiesConnectTimeoutThroughResourceAccessWrapper() {
        Throwable wrapped = new ResourceAccessException(
                "I/O error", new SocketTimeoutException("Connect timed out"));
        assertThat(MunicipalSourceFailureClassifier.classify(wrapped))
                .isEqualTo(MunicipalSourceFailureCategory.CONNECT_TIMEOUT);
    }

    @Test
    void classifiesDnsFailure() {
        assertThat(MunicipalSourceFailureClassifier.classify(new UnknownHostException("openapi.example")))
                .isEqualTo(MunicipalSourceFailureCategory.DNS_RESOLUTION);
    }

    @Test
    void classifiesConnectionRefused() {
        assertThat(MunicipalSourceFailureClassifier.classify(new ConnectException("Connection refused")))
                .isEqualTo(MunicipalSourceFailureCategory.CONNECTION_REFUSED);
    }

    @Test
    void classifiesTlsFailure() {
        assertThat(MunicipalSourceFailureClassifier.classify(new SSLException("handshake")))
                .isEqualTo(MunicipalSourceFailureCategory.TLS_FAILURE);
    }

    @Test
    void classifiesHttpStatusFamilies() {
        assertThat(MunicipalSourceFailureClassifier.classify(
                        HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null)))
                .isEqualTo(MunicipalSourceFailureCategory.UPSTREAM_4XX);
        assertThat(MunicipalSourceFailureClassifier.classify(
                        HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "auth", null, null, null)))
                .isEqualTo(MunicipalSourceFailureCategory.AUTHENTICATION);
        assertThat(MunicipalSourceFailureClassifier.classify(
                        HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rl", null, null, null)))
                .isEqualTo(MunicipalSourceFailureCategory.RATE_LIMITED);
        assertThat(MunicipalSourceFailureClassifier.classify(
                        HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bg", null, null, null)))
                .isEqualTo(MunicipalSourceFailureCategory.UPSTREAM_5XX);
    }

    @Test
    void classifiesSchemaContractAndDeserialization() {
        assertThat(MunicipalSourceFailureClassifier.classify(new IllegalArgumentException("contract mismatch")))
                .isEqualTo(MunicipalSourceFailureCategory.SCHEMA_CONTRACT);
        assertThat(MunicipalSourceFailureClassifier.classify(new JsonParseException(null, "bad json")))
                .isEqualTo(MunicipalSourceFailureCategory.DESERIALIZATION);
        assertThat(MunicipalSourceFailureClassifier.classify(new IllegalArgumentException("invalid record")))
                .isEqualTo(MunicipalSourceFailureCategory.INVALID_SOURCE_DATA);
    }

    @Test
    void classifiesDatabaseAndUnknown() {
        assertThat(MunicipalSourceFailureClassifier.classify(new QueryTimeoutException("db")))
                .isEqualTo(MunicipalSourceFailureCategory.DATABASE);
        assertThat(MunicipalSourceFailureClassifier.classify(new RuntimeException("surprise")))
                .isEqualTo(MunicipalSourceFailureCategory.UNKNOWN);
    }

    @Test
    void readTimeoutNeverBecomesSchemaContract() {
        Throwable wrapped = new ResourceAccessException(
                "I/O error on GET", new SocketTimeoutException("Read timed out"));
        assertThat(MunicipalSourceFailureClassifier.classify(wrapped))
                .isNotEqualTo(MunicipalSourceFailureCategory.SCHEMA_CONTRACT)
                .isEqualTo(MunicipalSourceFailureCategory.READ_TIMEOUT);
    }

    @Test
    void preservesRootCategoryAcrossRetryExhaustionWrapper() {
        RuntimeException exhausted = new RuntimeException(
                "retries exhausted",
                new ResourceAccessException("I/O", new SocketTimeoutException("Read timed out")));
        assertThat(MunicipalSourceFailureClassifier.classify(exhausted))
                .isEqualTo(MunicipalSourceFailureCategory.READ_TIMEOUT);
    }
}
