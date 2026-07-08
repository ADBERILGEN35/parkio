package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.List;

public interface WaitlistInterestRepository {

    void insertIfAbsent(WaitlistInterest interest);

    List<WaitlistExportRow> export(Instant createdFrom, Instant createdTo);
}
