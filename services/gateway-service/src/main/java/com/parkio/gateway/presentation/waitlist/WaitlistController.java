package com.parkio.gateway.presentation.waitlist;

import com.parkio.gateway.application.waitlist.SubmitWaitlistCommand;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistExportRow;
import com.parkio.gateway.infrastructure.config.ClientIpResolver;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class WaitlistController {

    private static final WaitlistAcceptedResponse ACCEPTED = new WaitlistAcceptedResponse("accepted");

    private final WaitlistApplicationService waitlistService;
    private final ClientIpResolver clientIpResolver;

    public WaitlistController(WaitlistApplicationService waitlistService, ClientIpResolver clientIpResolver) {
        this.waitlistService = waitlistService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/api/v1/waitlist")
    public Mono<ResponseEntity<WaitlistAcceptedResponse>> submit(
            @Valid @RequestBody SubmitWaitlistRequest request,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
            ServerWebExchange exchange) {
        String clientIp = clientIpResolver.resolve(exchange.getRequest());
        SubmitWaitlistCommand command = new SubmitWaitlistCommand(
                request.email(),
                request.consentTimestamp(),
                request.city(),
                request.role(),
                request.source(),
                clientIp,
                userAgent);
        return waitlistService.submit(command)
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(ACCEPTED));
    }

    @GetMapping(value = "/api/v1/waitlist/export", produces = "text/csv")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo) {
        return waitlistService.export(createdFrom, createdTo)
                .map(rows -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment()
                                        .filename("parkio-waitlist.csv")
                                        .build()
                                        .toString())
                        .body(toCsv(rows).getBytes(StandardCharsets.UTF_8)));
    }

    private static String toCsv(List<WaitlistExportRow> rows) {
        StringBuilder csv = new StringBuilder("email,city,role,source,createdAt,consentTimestamp\n");
        for (WaitlistExportRow row : rows) {
            csv.append(csv(row.email())).append(',')
                    .append(csv(row.city())).append(',')
                    .append(csv(row.role())).append(',')
                    .append(csv(row.source())).append(',')
                    .append(csv(row.createdAt().toString())).append(',')
                    .append(csv(row.consentTimestamp().toString())).append('\n');
        }
        return csv.toString();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
