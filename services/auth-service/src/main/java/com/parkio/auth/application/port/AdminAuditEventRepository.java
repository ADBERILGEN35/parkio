package com.parkio.auth.application.port;

import com.parkio.auth.application.admin.AdminAuditSearchQuery;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.domain.admin.AdminAuditEvent;
import java.util.List;
import java.util.UUID;

public interface AdminAuditEventRepository {

    void save(AdminAuditEvent event);

    PageResult<AdminAuditEvent> search(AdminAuditSearchQuery query);

    List<AdminAuditEvent> findRecentForTarget(String targetResourceType, UUID targetResourceId, int limit);
}
