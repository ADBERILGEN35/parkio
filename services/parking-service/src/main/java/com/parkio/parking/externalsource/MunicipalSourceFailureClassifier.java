package com.parkio.parking.externalsource;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * Canonical municipal-source failure classifier. Walks the cause chain root-first
 * so Spring {@code ResourceAccessException} wrappers preserve SocketTimeoutException
 * (and similar) categories instead of falling through to schema_contract/unknown.
 */
public final class MunicipalSourceFailureClassifier {
    private static final Set<String> CONNECT_TIMEOUT_NAMES = Set.of(
            "connecttimeoutexception",
            "httpconnecttimeoutexception");
    private static final Set<String> READ_TIMEOUT_NAMES = Set.of(
            "readtimeoutexception",
            "httptimeoutexception",
            "sockettimeoutexception");

    private MunicipalSourceFailureClassifier() {}

    public static MunicipalSourceFailureCategory classify(Throwable failure) {
        if (failure == null) {
            return MunicipalSourceFailureCategory.UNKNOWN;
        }
        if (Thread.currentThread().isInterrupted() || containsType(failure, InterruptedException.class)) {
            return MunicipalSourceFailureCategory.CANCELLED;
        }
        List<Throwable> chain = causeChainRootFirst(failure);
        for (Throwable node : chain) {
            Optional<MunicipalSourceFailureCategory> exact = classifyExact(node);
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        return MunicipalSourceFailureCategory.UNKNOWN;
    }

    public static String wireValue(Throwable failure) {
        return classify(failure).wireValue();
    }

    private static Optional<MunicipalSourceFailureCategory> classifyExact(Throwable node) {
        if (node instanceof RestClientResponseException response) {
            return Optional.of(fromHttpStatus(response.getStatusCode()));
        }
        if (node instanceof DataAccessException) {
            return Optional.of(MunicipalSourceFailureCategory.DATABASE);
        }
        if (node instanceof JsonProcessingException) {
            return Optional.of(MunicipalSourceFailureCategory.DESERIALIZATION);
        }
        if (node instanceof UnknownHostException) {
            return Optional.of(MunicipalSourceFailureCategory.DNS_RESOLUTION);
        }
        if (node instanceof SSLException) {
            return Optional.of(MunicipalSourceFailureCategory.TLS_FAILURE);
        }
        if (node instanceof ConnectException connect) {
            String message = safeLower(connect.getMessage());
            if (message.contains("timed out") || message.contains("timeout")) {
                return Optional.of(MunicipalSourceFailureCategory.CONNECT_TIMEOUT);
            }
            return Optional.of(MunicipalSourceFailureCategory.CONNECTION_REFUSED);
        }
        if (node instanceof NoRouteToHostException) {
            return Optional.of(MunicipalSourceFailureCategory.CONNECTION_REFUSED);
        }
        if (node instanceof SocketTimeoutException timeout) {
            return Optional.of(socketTimeoutCategory(timeout));
        }
        if (node instanceof SocketException socket) {
            String message = safeLower(socket.getMessage());
            if (message.contains("connection reset") || message.contains("broken pipe")) {
                return Optional.of(MunicipalSourceFailureCategory.CONNECTION_REFUSED);
            }
        }

        String simple = node.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (CONNECT_TIMEOUT_NAMES.contains(simple) || simple.contains("connecttimeout")) {
            return Optional.of(MunicipalSourceFailureCategory.CONNECT_TIMEOUT);
        }
        if (simple.contains("readtimeout") || simple.equals("httptimeoutexception")) {
            return Optional.of(MunicipalSourceFailureCategory.READ_TIMEOUT);
        }
        if (READ_TIMEOUT_NAMES.contains(simple)) {
            // SocketTimeoutException already handled; remaining name hits are read-oriented.
            return Optional.of(MunicipalSourceFailureCategory.READ_TIMEOUT);
        }
        if (simple.contains("unknownhost")) {
            return Optional.of(MunicipalSourceFailureCategory.DNS_RESOLUTION);
        }
        if (simple.contains("ssl") || simple.contains("tls")) {
            return Optional.of(MunicipalSourceFailureCategory.TLS_FAILURE);
        }

        // Schema / validation failures thrown by adapters after a successful HTTP fetch.
        if (node instanceof IllegalArgumentException || node instanceof IllegalStateException) {
            String message = safeLower(node.getMessage());
            if (message.contains("deserial") || message.contains("json")) {
                return Optional.of(MunicipalSourceFailureCategory.DESERIALIZATION);
            }
            if (message.contains("invalid") || message.contains("normalize")) {
                return Optional.of(MunicipalSourceFailureCategory.INVALID_SOURCE_DATA);
            }
            return Optional.of(MunicipalSourceFailureCategory.SCHEMA_CONTRACT);
        }
        return Optional.empty();
    }

    private static MunicipalSourceFailureCategory fromHttpStatus(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return MunicipalSourceFailureCategory.AUTHENTICATION;
        }
        if (code == 429) {
            return MunicipalSourceFailureCategory.RATE_LIMITED;
        }
        if (code == 413) {
            return MunicipalSourceFailureCategory.RESPONSE_TOO_LARGE;
        }
        if (status.is4xxClientError()) {
            return MunicipalSourceFailureCategory.UPSTREAM_4XX;
        }
        if (status.is5xxServerError()) {
            return MunicipalSourceFailureCategory.UPSTREAM_5XX;
        }
        return MunicipalSourceFailureCategory.UNKNOWN;
    }

    /**
     * JDK {@link SocketTimeoutException} covers both connect and read timeouts for
     * {@code SimpleClientHttpRequestFactory}. Prefer typed subclass names when present;
     * otherwise use the standard JDK messages ("Connect timed out" / "Read timed out").
     */
    private static MunicipalSourceFailureCategory socketTimeoutCategory(SocketTimeoutException timeout) {
        String simple = timeout.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (simple.contains("connect")) {
            return MunicipalSourceFailureCategory.CONNECT_TIMEOUT;
        }
        if (simple.contains("read")) {
            return MunicipalSourceFailureCategory.READ_TIMEOUT;
        }
        String message = safeLower(timeout.getMessage());
        if (message.contains("connect")) {
            return MunicipalSourceFailureCategory.CONNECT_TIMEOUT;
        }
        // Default SocketTimeoutException from read path uses "Read timed out".
        return MunicipalSourceFailureCategory.READ_TIMEOUT;
    }

    private static List<Throwable> causeChainRootFirst(Throwable failure) {
        List<Throwable> outerFirst = new ArrayList<>();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = failure; current != null && seen.put(current, Boolean.TRUE) == null;
                current = current.getCause()) {
            outerFirst.add(current);
        }
        List<Throwable> rootFirst = new ArrayList<>(outerFirst.size());
        for (int i = outerFirst.size() - 1; i >= 0; i--) {
            rootFirst.add(outerFirst.get(i));
        }
        return rootFirst;
    }

    private static boolean containsType(Throwable failure, Class<? extends Throwable> type) {
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = failure; current != null && seen.put(current, Boolean.TRUE) == null;
                current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static String safeLower(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
