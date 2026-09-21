package com.parkio.gateway.presentation.waitlist;

import com.parkio.gateway.application.waitlist.SubmitWaitlistCommand;
import com.parkio.gateway.application.waitlist.WaitlistAdminCounts;
import com.parkio.gateway.application.waitlist.WaitlistAdminPage;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistCsv;
import com.parkio.gateway.application.waitlist.WaitlistExportRow;
import com.parkio.gateway.application.waitlist.WaitlistStatus;
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
    private static final WaitlistAcceptedResponse CONFIRMED = new WaitlistAcceptedResponse("confirmed");
    private static final WaitlistAcceptedResponse WITHDRAWN = new WaitlistAcceptedResponse("withdrawn");
    private static final String NO_STORE = "no-store";

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
                request.locale(),
                clientIp,
                userAgent);
        return waitlistService.submit(command)
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(ACCEPTED));
    }

    @PostMapping("/api/v1/waitlist/confirm")
    public Mono<ResponseEntity<WaitlistAcceptedResponse>> confirm(@Valid @RequestBody WaitlistTokenRequest request) {
        return waitlistService.confirm(request.token())
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(CONFIRMED));
    }

    @PostMapping("/api/v1/waitlist/withdraw")
    public Mono<ResponseEntity<WaitlistAcceptedResponse>> withdraw(@Valid @RequestBody WaitlistTokenRequest request) {
        return waitlistService.withdraw(request.token())
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(WITHDRAWN));
    }

    @PostMapping("/api/v1/waitlist/resend")
    public Mono<ResponseEntity<WaitlistAcceptedResponse>> resend(
            @Valid @RequestBody ResendWaitlistRequest request,
            ServerWebExchange exchange) {
        String clientIp = clientIpResolver.resolve(exchange.getRequest());
        return waitlistService.resend(request.email(), clientIp)
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(ACCEPTED));
    }

    /**
     * Operator visibility for notification-list subscriptions (not application accounts).
     * Authorization is enforced at the gateway edge ({@code ADMIN}/{@code SUPER_ADMIN}).
     */
    @GetMapping("/api/v1/waitlist/admin/summary")
    public Mono<ResponseEntity<WaitlistAdminCounts>> adminSummary() {
        return waitlistService.adminCounts()
                .map(counts -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        .body(counts));
    }

    @GetMapping("/api/v1/waitlist/admin")
    public Mono<ResponseEntity<WaitlistAdminPage>> adminList(
            @RequestParam(required = false) WaitlistStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return waitlistService.adminList(status, createdFrom, createdTo, page, size)
                .map(result -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        .body(result));
    }

    @GetMapping(value = "/api/v1/waitlist/export", produces = "text/csv")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo) {
        return waitlistService.export(createdFrom, createdTo)
                .map(rows -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment()
                                        .filename("parkio-waitlist-confirmed.csv")
                                        .build()
                                        .toString())
                        .body(toCsv(rows).getBytes(StandardCharsets.UTF_8)));
    }

    private static String toCsv(List<WaitlistExportRow> rows) {
        StringBuilder csv = new StringBuilder("email,city,role,source,createdAt,consentTimestamp\n");
        for (WaitlistExportRow row : rows) {
            csv.append(WaitlistCsv.cell(row.email())).append(',')
                    .append(WaitlistCsv.cell(row.city())).append(',')
                    .append(WaitlistCsv.cell(row.role())).append(',')
                    .append(WaitlistCsv.cell(row.source())).append(',')
                    .append(WaitlistCsv.cell(row.createdAt().toString())).append(',')
                    .append(WaitlistCsv.cell(row.consentTimestamp().toString())).append('\n');
        }
        return csv.toString();
    }
}
