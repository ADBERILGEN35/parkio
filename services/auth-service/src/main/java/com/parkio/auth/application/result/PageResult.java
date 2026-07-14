package com.parkio.auth.application.result;

import java.util.List;

/**
 * Framework-free pagination slice returned by application services.
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
