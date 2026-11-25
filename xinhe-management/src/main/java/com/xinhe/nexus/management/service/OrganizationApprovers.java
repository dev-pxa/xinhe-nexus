package com.xinhe.nexus.management.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finds the nearest active superior in the same company, skipping organizations led by the applicant. */
final class OrganizationApprovers {
    private OrganizationApprovers() {}

    static Map<String,Object> nearest(JdbcTemplate db, long companyId, long applicantId, String permission) {
        var rows = db.queryForList("""
                SELECT department.id AS applicantOrgId,department.org_name AS departmentName,
                       o.id AS orgUnitId,o.org_name AS orgName,manager.id,manager.display_name AS name,
                       o.manager_position_id AS managerPositionId,pos.position_name AS reviewerPositionName,
                       CASE WHEN mc.is_company_admin=1 THEN 1 ELSE 0 END AS managerIsAdmin
                FROM sys_user_position up
                JOIN sys_position p ON p.id=up.position_id AND p.company_id=up.company_id
                    AND p.status='ENABLED' AND p.deleted_at IS NULL
                JOIN sys_org_unit department ON department.id=p.org_unit_id AND department.company_id=p.company_id
                    AND department.status='ENABLED' AND department.deleted_at IS NULL
                JOIN sys_org_unit o ON o.company_id=department.company_id
                    AND department.org_path LIKE CONCAT(o.org_path,'%')
                    AND o.status='ENABLED' AND o.deleted_at IS NULL
                LEFT JOIN sys_position pos ON pos.id=o.manager_position_id AND pos.company_id=o.company_id
                    AND pos.status='ENABLED' AND pos.deleted_at IS NULL
                JOIN sys_user_company mc ON mc.company_id=o.company_id AND mc.status='ENABLED' AND mc.deleted_at IS NULL
                JOIN sys_user manager ON manager.id=mc.user_id AND manager.status='ENABLED' AND manager.deleted_at IS NULL
                WHERE up.company_id=? AND up.user_id=? AND up.status='ENABLED' AND up.deleted_at IS NULL
                  AND manager.id<>up.user_id
                  AND NOT (COALESCE(o.manager_user_id,0)=up.user_id OR EXISTS (
                      SELECT 1 FROM sys_user_position own
                      WHERE own.company_id=o.company_id AND own.position_id=o.manager_position_id
                        AND own.user_id=up.user_id AND own.status='ENABLED' AND own.deleted_at IS NULL))
                  AND (manager.id=o.manager_user_id OR EXISTS (
                      SELECT 1 FROM sys_user_position mp WHERE mp.company_id=o.company_id
                        AND mp.position_id=pos.id AND mp.user_id=manager.id
                        AND mp.status='ENABLED' AND mp.deleted_at IS NULL))
                  AND (? IS NULL OR mc.is_company_admin=1 OR EXISTS (
                      SELECT 1 FROM sys_user_permission direct_permission
                      JOIN sys_permission sp ON sp.id=direct_permission.permission_id
                      WHERE direct_permission.company_id=mc.company_id AND direct_permission.user_id=manager.id
                        AND sp.permission_code IN (?, '*') AND sp.status='ENABLED' AND sp.deleted_at IS NULL)
                    OR EXISTS (
                      SELECT 1 FROM sys_user_position role
                      JOIN sys_position rp ON rp.id=role.position_id AND rp.company_id=role.company_id
                        AND rp.status='ENABLED' AND rp.deleted_at IS NULL
                      JOIN sys_position_permission pp ON pp.position_id=rp.id AND pp.company_id=rp.company_id
                      JOIN sys_permission sp ON sp.id=pp.permission_id AND sp.status='ENABLED' AND sp.deleted_at IS NULL
                      WHERE role.company_id=mc.company_id AND role.user_id=manager.id
                        AND role.status='ENABLED' AND role.deleted_at IS NULL AND sp.permission_code IN (?, '*')))
                ORDER BY o.org_level DESC,manager.id=o.manager_user_id DESC,manager.id LIMIT 1
                """, companyId,applicantId,permission,permission,permission);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
