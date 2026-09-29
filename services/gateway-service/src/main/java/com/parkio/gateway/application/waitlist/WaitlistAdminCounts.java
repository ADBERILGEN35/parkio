package com.parkio.gateway.application.waitlist;

public record WaitlistAdminCounts(long pending, long confirmed, long withdrawn, long total) {
}
