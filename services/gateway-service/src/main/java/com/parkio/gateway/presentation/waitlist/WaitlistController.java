package com.parkio.gateway.presentation.waitlist;

import com.parkio.gateway.application.waitlist.SubmitWaitlistCommand;
import com.parkio.gateway.application.waitlist.WaitlistAdminCounts;
import com.parkio.gateway.application.waitlist.WaitlistAdminPage;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistCsv;
import com.parkio.gateway.application.waitlist.WaitlistExport;
import com.parkio.gateway.application.waitlist.WaitlistExportFilterException;
import com.parkio.gateway.application.waitlist.WaitlistExportRow;
import com.parkio.gateway.application.waitlist.WaitlistStatus;
import com.parkio.gateway.infrastructure.config.ClientIpResolver;
import com.parkio.gateway.shared.GatewayHeaders;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class WaitlistController {

    private static final WaitlistAcceptedResponse ACCEPTED = new WaitlistAcceptedResponse("accepted");
    private static final WaitlistAcceptedResponse CONFIRMED = new WaitlistAcceptedResponse("confirmed");
    private static final WaitlistAcceptedResponse WITHDRAWN = new WaitlistAcceptedResponse("withdrawn");
    private static final String NO_STORE = "no-store";
    private static final String CSV_HEADER = "email,fullName,city,role,source,createdAt,consentTimestamp\n";

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
                request.fullName(),
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

    /**
     * Confirmed subscriptions as CSV (UTF-8 with a BOM, for spreadsheet clients), filtered by
     * confirmation time and streamed one bounded page at a time. At most
     * {@code parkio.waitlist.export.max-rows} rows are returned; the response headers say how many
     * rows matched and whether the export was truncated. {@code createdFrom}/{@code createdTo} are
     * refused: the export filters by confirmation time, not registration time.
     */
    @GetMapping(value = "/api/v1/waitlist/export", produces = "text/csv")
    public Mono<ResponseEntity<Flux<DataBuffer>>> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant confirmedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant confirmedTo,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo) {
        if (createdFrom != null || createdTo != null) {
            return Mono.error(new WaitlistExportFilterException(
                    "The confirmed export filters by confirmation time; use confirmedFrom and confirmedTo."));
        }
        return waitlistService.export(confirmedFrom, confirmedTo)
                .map(export -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment()
                                        .filename("parkio-waitlist-confirmed.csv")
                                        .build()
                                        .toString())
                        .header(GatewayHeaders.EXPORT_ROW_LIMIT, Integer.toString(export.rowLimit()))
                        .header(GatewayHeaders.EXPORT_MATCHING_ROWS, Long.toString(export.matchingRows()))
                        .header(GatewayHeaders.EXPORT_TRUNCATED, Boolean.toString(export.truncated()))
                        .body(csvBody(export)));
    }

    private static Flux<DataBuffer> csvBody(WaitlistExport export) {
        DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] header = CSV_HEADER.getBytes(StandardCharsets.UTF_8);
        byte[] head = new byte[bom.length + header.length];
        System.arraycopy(bom, 0, head, 0, bom.length);
        System.arraycopy(header, 0, head, bom.length, header.length);
        return Flux.concat(
                Flux.just(buffers.wrap(head)),
                export.pages().map(page -> buffers.wrap(toCsvRows(page).getBytes(StandardCharsets.UTF_8))));
    }

    private static String toCsvRows(List<WaitlistExportRow> rows) {
        StringBuilder csv = new StringBuilder();
        for (WaitlistExportRow row : rows) {
            csv.append(WaitlistCsv.cell(row.email())).append(',')
                    .append(WaitlistCsv.cell(row.fullName())).append(',')
                    .append(WaitlistCsv.cell(row.city())).append(',')
                    .append(WaitlistCsv.cell(row.role())).append(',')
                    .append(WaitlistCsv.cell(row.source())).append(',')
                    .append(WaitlistCsv.cell(row.createdAt().toString())).append(',')
                    .append(WaitlistCsv.cell(row.consentTimestamp().toString())).append('\n');
        }
        return csv.toString();
    }
}
