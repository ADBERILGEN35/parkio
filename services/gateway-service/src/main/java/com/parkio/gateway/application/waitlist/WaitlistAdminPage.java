package com.parkio.gateway.application.waitlist;

import java.util.List;

public record WaitlistAdminPage(
        List<WaitlistAdminEntry> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
