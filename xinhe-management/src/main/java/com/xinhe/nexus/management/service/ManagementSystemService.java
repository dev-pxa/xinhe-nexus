package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ManagementDtos.CompanyCreate;
import com.xinhe.nexus.management.service.ManagementDtos.CompanyUpdate;
import com.xinhe.nexus.management.service.ManagementDtos.OrgSave;
import com.xinhe.nexus.management.service.ManagementDtos.PositionPermissions;
import com.xinhe.nexus.management.service.ManagementDtos.PositionSave;
import com.xinhe.nexus.management.service.ManagementDtos.ResetPassword;
import com.xinhe.nexus.management.service.ManagementDtos.UserCreate;
import com.xinhe.nexus.management.service.ManagementDtos.UserPositions;
import com.xinhe.nexus.management.service.ManagementDtos.UserUpdate;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementSystemService {
    private static final Pattern CONTACT_PHONE_CHARACTERS = Pattern.compile("^[0-9+()\\- ]+$");

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public ManagementSystemService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> listCompanies(ManagementUser actor) {
        if (isPlatform(actor)) {
            return jdbcTemplate.queryForList("""
                    SELECT c.id, c.company_code AS companyCode, c.company_name AS companyName,
                           c.channel_level AS channelLevel, c.contact_name AS contactName,
                           c.contact_phone AS contactPhone, c.status, c.auth_version AS authVersion,
                           c.version, r.upstream_company_id AS upstreamCompanyId,
                           up.company_name AS upstreamCompanyName, c.created_at AS createdAt,
                           c.updated_at AS updatedAt
                    FROM sys_company c
                    LEFT JOIN sys_company_relation r ON r.downstream_company_id = c.id
                        AND r.status = 'ENABLED' AND r.deleted_at IS NULL
                    LEFT JOIN sys_company up ON up.id = r.upstream_company_id
                    WHERE c.deleted_at IS NULL
                    ORDER BY c.channel_level, c.company_code
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT id, company_code AS companyCode, company_name AS companyName,
                       channel_level AS channelLevel, contact_name AS contactName,
                       contact_phone AS contactPhone, status, auth_version AS authVersion,
                       version, created_at AS createdAt, updated_at AS updatedAt
                FROM sys_company WHERE id = ? AND deleted_at IS NULL
                """, actor.companyId());
    }

    @Transactional
    public Map<String, Object> createCompany(ManagementUser actor, CompanyCreate body) {
        requirePlatform(actor);
        String code = normalizeCode(body.companyCode());
        String name = required(body.companyName(), "公司名称");
        int channelLevel = 1;
        if (body.upstreamCompanyId() != null) {
            Integer parentLevel = jdbcTemplate.queryForObject(
                    "SELECT channel_level FROM sys_company WHERE id = ? AND status = 'ENABLED' AND deleted_at IS NULL",
                    Integer.class, body.upstreamCompanyId());
            if (parentLevel == null || parentLevel >= 4) {
                throw badRequest("上级公司的渠道层级无效，渠道关系最多四级");
            }
            channelLevel = parentLevel + 1;
        }
        long companyId = insertAndReturnId(
                """
                INSERT INTO sys_company
                    (company_code, company_name, channel_level, contact_name, contact_phone, status, created_by)
                VALUES (?, ?, ?, ?, ?, 'ENABLED', ?)
                """, code, name, channelLevel, trim(body.contactName()), trim(body.contactPhone()), actor.id());
        if (body.upstreamCompanyId() != null) {
            jdbcTemplate.update("""
                    INSERT INTO sys_company_relation
                        (upstream_company_id, downstream_company_id, status, created_by)
                    VALUES (?, ?, 'ENABLED', ?)
                    """, body.upstreamCompanyId(), companyId, actor.id());
        }
        createRootOrganization(companyId, code, name, actor.id());
        long adminId = insertAndReturnId("""
                INSERT INTO sys_user
                    (username, password_hash, display_name, user_type, status, must_change_password, created_by)
                VALUES (?, ?, ?, 'COMPANY_USER', 'ENABLED', 1, ?)
                """, code, passwordEncoder.encode(code), name + "管理员", actor.id());
        jdbcTemplate.update("""
                INSERT INTO sys_user_company
                    (user_id, company_id, is_company_admin, status, created_by)
                VALUES (?, ?, 1, 'ENABLED', ?)
                """, adminId, companyId, actor.id());
        audit(actor, companyId, "COMPANY", "CREATE", companyId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("companyId", companyId);
        result.put("companyCode", code);
        result.put("companyName", name);
        result.put("channelLevel", channelLevel);
        result.put("administratorUsername", code);
        result.put("initialPassword", code);
        return result;
    }

    @Transactional
    public void updateCompany(ManagementUser actor, long id, CompanyUpdate body) {
        requirePlatform(actor);
        int changed = jdbcTemplate.update("""
                UPDATE sys_company
                SET company_name = ?, contact_name = ?, contact_phone = ?, status = ?,
                    version = version + 1, updated_by = ?
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                """, required(body.companyName(), "公司名称"), trim(body.contactName()), trim(body.contactPhone()),
                status(body.status()), actor.id(), id, body.version());
        requireUpdated(changed);
        jdbcTemplate.update("UPDATE sys_org_unit SET org_name = ?, updated_by = ? WHERE company_id = ? AND parent_id IS NULL AND deleted_at IS NULL",
                body.companyName().trim(), actor.id(), id);
        audit(actor, id, "COMPANY", "UPDATE", id);
    }

    public List<Map<String, Object>> listOrganizations(ManagementUser actor, Long requestedCompanyId) {
        long companyId = resolveCompany(actor, requestedCompanyId);
        return jdbcTemplate.queryForList("""
                SELECT o.id, o.company_id AS companyId, o.parent_id AS parentId,
                       o.org_code AS orgCode, o.org_name AS orgName, o.org_path AS orgPath,
                       o.org_level AS orgLevel, o.manager_user_id AS managerUserId,
                       u.display_name AS managerName, o.contact_phone AS contactPhone,
                       o.description, o.status, o.version, o.created_at AS createdAt,
                       o.updated_at AS updatedAt,
                       CASE WHEN o.status = 'ENABLED' AND NOT EXISTS (
                           SELECT 1 FROM sys_org_unit a
                           WHERE a.company_id = o.company_id AND a.deleted_at IS NULL
                             AND a.status = 'DISABLED' AND o.org_path LIKE CONCAT(a.org_path, '%')
                       ) THEN 1 ELSE 0 END AS effectiveEnabled
                FROM sys_org_unit o
                LEFT JOIN sys_user u ON u.id = o.manager_user_id AND u.deleted_at IS NULL
                WHERE o.company_id = ? AND o.deleted_at IS NULL
                ORDER BY o.org_level, o.org_name, o.created_at
                """, companyId);
    }

    @Transactional
    public Map<String, Object> createOrganization(ManagementUser actor, Long requestedCompanyId, OrgSave body) {
        requireManager(actor, "system:org:create");
        long companyId = resolveCompany(actor, requestedCompanyId);
        String contactPhone = normalizeContactPhone(body.contactPhone());
        ParentOrg parent = parentOrganization(companyId, body.parentId());
        if (!"ENABLED".equals(parent.status())) {
            throw badRequest("上级组织已停用，不能创建下级组织");
        }
        String pendingCode = "PENDING-" + UUID.randomUUID().toString().substring(0, 8);
        long id = insertAndReturnId("""
                INSERT INTO sys_org_unit
                    (company_id, parent_id, org_code, org_name, org_path, org_level,
                     manager_user_id, contact_phone, description, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, companyId, parent.id(), pendingCode, required(body.orgName(), "组织名称"),
                parent.path() + "pending/", parent.level() + 1, body.managerUserId(),
                contactPhone, trim(body.description()), status(body.status()), actor.id());
        String companyCode = companyCode(companyId);
        String code = "ORG-" + companyCode + "-" + String.format("%06d", id);
        String path = parent.path() + id + "/";
        jdbcTemplate.update("UPDATE sys_org_unit SET org_code = ?, org_path = ? WHERE id = ?", code, path, id);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "ORG", "CREATE", id);
        return Map.of("id", id, "orgCode", code, "orgPath", path);
    }

    @Transactional
    public void updateOrganization(ManagementUser actor, long id, OrgSave body) {
        requireManager(actor, "system:org:update");
        String contactPhone = normalizeContactPhone(body.contactPhone());
        Map<String, Object> org = organization(id);
        long companyId = ((Number) org.get("companyId")).longValue();
        resolveCompany(actor, companyId);
        int expectedVersion = body.version() == null ? -1 : body.version();
        int changed = jdbcTemplate.update("""
                UPDATE sys_org_unit
                SET org_name = ?, manager_user_id = ?, contact_phone = ?, description = ?, status = ?,
                    version = version + 1, updated_by = ?
                WHERE id = ? AND company_id = ? AND version = ? AND deleted_at IS NULL
                """, required(body.orgName(), "组织名称"), body.managerUserId(), contactPhone,
                trim(body.description()), status(body.status()), actor.id(), id, companyId, expectedVersion);
        requireUpdated(changed);
        Long currentParent = (Long) org.get("parentId");
        if (currentParent == null && body.parentId() != null) {
            throw badRequest("公司根组织不能移动");
        }
        if (currentParent != null && body.parentId() != null && !currentParent.equals(body.parentId())) {
            moveOrganization(companyId, id, body.parentId());
        }
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "ORG", "UPDATE", id);
    }

    public Map<String, Object> organizationDeleteImpact(ManagementUser actor, long id) {
        Map<String, Object> org = organization(id);
        long companyId = ((Number) org.get("companyId")).longValue();
        resolveCompany(actor, companyId);
        String path = (String) org.get("orgPath");
        int children = count("SELECT COUNT(*) FROM sys_org_unit WHERE company_id = ? AND org_path LIKE ? AND id <> ? AND deleted_at IS NULL",
                companyId, path + "%", id);
        int positions = count("SELECT COUNT(*) FROM sys_position WHERE company_id = ? AND org_unit_id = ? AND deleted_at IS NULL",
                companyId, id);
        int users = count("""
                SELECT COUNT(DISTINCT up.user_id) FROM sys_user_position up
                JOIN sys_position p ON p.id = up.position_id AND p.deleted_at IS NULL
                WHERE up.company_id = ? AND p.org_unit_id = ? AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                """, companyId, id);
        boolean root = org.get("parentId") == null;
        return Map.of("childOrganizations", children, "positions", positions, "users", users,
                "rootOrganization", root, "canDelete", !root && children == 0 && positions == 0 && users == 0);
    }

    @Transactional
    public void deleteOrganization(ManagementUser actor, long id) {
        requireManager(actor, "system:org:delete");
        Map<String, Object> impact = organizationDeleteImpact(actor, id);
        if (!Boolean.TRUE.equals(impact.get("canDelete"))) {
            throw badRequest("该组织仍有下级组织、岗位或用户，不能删除");
        }
        Map<String, Object> org = organization(id);
        long companyId = ((Number) org.get("companyId")).longValue();
        jdbcTemplate.update("UPDATE sys_org_unit SET deleted_at = NOW(), updated_by = ? WHERE id = ?", actor.id(), id);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "ORG", "DELETE", id);
    }

    public List<Map<String, Object>> listPositions(ManagementUser actor, Long requestedCompanyId, Long orgUnitId) {
        long companyId = resolveCompany(actor, requestedCompanyId);
        return jdbcTemplate.queryForList("""
                SELECT p.id, p.company_id AS companyId, p.org_unit_id AS orgUnitId,
                       o.org_name AS orgName, p.position_code AS positionCode,
                       p.position_name AS positionName, p.responsibilities, p.status, p.version,
                       COUNT(DISTINCT up.user_id) AS userCount,
                       COUNT(DISTINCT pp.permission_id) AS permissionCount,
                       p.created_at AS createdAt, p.updated_at AS updatedAt
                FROM sys_position p
                JOIN sys_org_unit o ON o.id = p.org_unit_id AND o.company_id = p.company_id
                LEFT JOIN sys_user_position up ON up.position_id = p.id
                    AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                LEFT JOIN sys_position_permission pp ON pp.position_id = p.id
                WHERE p.company_id = ? AND p.deleted_at IS NULL
                  AND (? IS NULL OR p.org_unit_id = ?)
                GROUP BY p.id, p.company_id, p.org_unit_id, o.org_name, p.position_code,
                         p.position_name, p.responsibilities, p.status, p.version,
                         p.created_at, p.updated_at
                ORDER BY o.org_name, p.position_name, p.created_at
                """, companyId, orgUnitId, orgUnitId);
    }

    @Transactional
    public Map<String, Object> createPosition(ManagementUser actor, Long requestedCompanyId, PositionSave body) {
        requireManager(actor, "system:position:create");
        long companyId = resolveCompany(actor, requestedCompanyId);
        organizationInCompany(companyId, body.orgUnitId());
        String pendingCode = "PENDING-" + UUID.randomUUID().toString().substring(0, 8);
        long id = insertAndReturnId("""
                INSERT INTO sys_position
                    (company_id, org_unit_id, position_code, position_name, responsibilities, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, companyId, body.orgUnitId(), pendingCode, required(body.positionName(), "岗位名称"),
                trim(body.responsibilities()), status(body.status()), actor.id());
        String code = "POS-" + companyCode(companyId) + "-" + String.format("%06d", id);
        jdbcTemplate.update("UPDATE sys_position SET position_code = ? WHERE id = ?", code, id);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "POSITION", "CREATE", id);
        return Map.of("id", id, "positionCode", code);
    }

    @Transactional
    public void updatePosition(ManagementUser actor, long id, PositionSave body) {
        requireManager(actor, "system:position:update");
        long companyId = positionCompany(id);
        resolveCompany(actor, companyId);
        organizationInCompany(companyId, body.orgUnitId());
        int changed = jdbcTemplate.update("""
                UPDATE sys_position
                SET org_unit_id = ?, position_name = ?, responsibilities = ?, status = ?,
                    version = version + 1, updated_by = ?
                WHERE id = ? AND company_id = ? AND version = ? AND deleted_at IS NULL
                """, body.orgUnitId(), required(body.positionName(), "岗位名称"), trim(body.responsibilities()),
                status(body.status()), actor.id(), id, companyId,
                body.version() == null ? -1 : body.version());
        requireUpdated(changed);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "POSITION", "UPDATE", id);
    }

    public Map<String, Object> positionDeleteImpact(ManagementUser actor, long id) {
        long companyId = positionCompany(id);
        resolveCompany(actor, companyId);
        int users = count("SELECT COUNT(*) FROM sys_user_position WHERE company_id = ? AND position_id = ? AND status = 'ENABLED' AND deleted_at IS NULL",
                companyId, id);
        return Map.of("users", users, "canDelete", users == 0);
    }

    @Transactional
    public void deletePosition(ManagementUser actor, long id) {
        requireManager(actor, "system:position:delete");
        Map<String, Object> impact = positionDeleteImpact(actor, id);
        if (!Boolean.TRUE.equals(impact.get("canDelete"))) {
            throw badRequest("该岗位仍有用户，不能删除");
        }
        long companyId = positionCompany(id);
        jdbcTemplate.update("UPDATE sys_position SET deleted_at = NOW(), updated_by = ? WHERE id = ?", actor.id(), id);
        jdbcTemplate.update("DELETE FROM sys_position_permission WHERE position_id = ?", id);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "POSITION", "DELETE", id);
    }

    public List<Map<String, Object>> listUsers(ManagementUser actor, Long requestedCompanyId, String keyword) {
        long companyId = resolveCompany(actor, requestedCompanyId);
        String search = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        return jdbcTemplate.queryForList("""
                SELECT u.id, u.username, u.display_name AS displayName, u.phone, u.email,
                       u.status, u.must_change_password AS mustChangePassword,
                       u.last_login_at AS lastLoginAt, u.version,
                       uc.company_id AS companyId, c.company_name AS companyName,
                       uc.is_company_admin AS companyAdmin,
                       GROUP_CONCAT(DISTINCT p.position_name ORDER BY p.position_name SEPARATOR '、') AS positionNames
                FROM sys_user u
                JOIN sys_user_company uc ON uc.user_id = u.id
                    AND uc.status = 'ENABLED' AND uc.deleted_at IS NULL
                JOIN sys_company c ON c.id = uc.company_id AND c.deleted_at IS NULL
                LEFT JOIN sys_user_position up ON up.user_id = u.id AND up.company_id = uc.company_id
                    AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                LEFT JOIN sys_position p ON p.id = up.position_id AND p.deleted_at IS NULL
                WHERE uc.company_id = ? AND u.deleted_at IS NULL
                  AND (u.username LIKE ? OR u.display_name LIKE ? OR COALESCE(u.phone, '') LIKE ?)
                GROUP BY u.id, u.username, u.display_name, u.phone, u.email, u.status,
                         u.must_change_password, u.last_login_at, u.version,
                         uc.company_id, c.company_name, uc.is_company_admin
                ORDER BY uc.is_company_admin DESC, u.created_at
                """, companyId, search, search, search);
    }

    @Transactional
    public Map<String, Object> createUser(ManagementUser actor, UserCreate body) {
        requireManager(actor, "system:user:create");
        long companyId = resolveCompany(actor, body.companyId());
        validatePassword(body.password());
        String username = required(body.username(), "登录账号");
        long userId = insertAndReturnId("""
                INSERT INTO sys_user
                    (username, password_hash, display_name, user_type, phone, email,
                     status, must_change_password, created_by)
                VALUES (?, ?, ?, 'COMPANY_USER', ?, ?, 'ENABLED', 1, ?)
                """, username, passwordEncoder.encode(body.password()), required(body.displayName(), "用户姓名"),
                trim(body.phone()), trim(body.email()), actor.id());
        jdbcTemplate.update("""
                INSERT INTO sys_user_company
                    (user_id, company_id, is_company_admin, status, created_by)
                VALUES (?, ?, ?, 'ENABLED', ?)
                """, userId, companyId, body.companyAdmin() ? 1 : 0, actor.id());
        replaceUserPositions(actor, companyId, userId,
                new UserPositions(body.positionIds() == null ? List.of() : body.positionIds(), null));
        audit(actor, companyId, "USER", "CREATE", userId);
        return Map.of("id", userId, "username", username);
    }

    @Transactional
    public void updateUser(ManagementUser actor, long id, UserUpdate body) {
        requireManager(actor, "system:user:update");
        long companyId = userCompany(id);
        resolveCompany(actor, companyId);
        boolean targetWasAdmin = isCompanyAdmin(id, companyId);
        boolean targetWillAdmin = body.companyAdmin() == null ? targetWasAdmin : body.companyAdmin();
        String targetStatus = status(body.status());
        if (targetWasAdmin && (!targetWillAdmin || "DISABLED".equals(targetStatus))) {
            requireAnotherCompanyAdmin(companyId, id);
        }
        int changed = jdbcTemplate.update("""
                UPDATE sys_user
                SET display_name = ?, phone = ?, email = ?, status = ?,
                    auth_version = auth_version + 1, version = version + 1, updated_by = ?
                WHERE id = ? AND version = ? AND user_type = 'COMPANY_USER' AND deleted_at IS NULL
                """, required(body.displayName(), "用户姓名"), trim(body.phone()), trim(body.email()),
                targetStatus, actor.id(), id, body.version() == null ? -1 : body.version());
        requireUpdated(changed);
        jdbcTemplate.update("UPDATE sys_user_company SET is_company_admin = ?, updated_by = ? WHERE user_id = ? AND company_id = ? AND deleted_at IS NULL",
                targetWillAdmin ? 1 : 0, actor.id(), id, companyId);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "USER", "UPDATE", id);
    }

    @Transactional
    public void replaceUserPositions(ManagementUser actor, long companyId, long userId, UserPositions body) {
        requireManager(actor, "system:user:assign-position");
        resolveCompany(actor, companyId);
        if (userCompany(userId) != companyId) {
            throw badRequest("用户不属于当前公司");
        }
        List<Long> positionIds = body.positionIds() == null ? List.of() : body.positionIds().stream().distinct().toList();
        for (Long positionId : positionIds) {
            if (positionCompany(positionId) != companyId) {
                throw badRequest("岗位不属于当前公司");
            }
        }
        jdbcTemplate.update("DELETE FROM sys_user_position WHERE company_id = ? AND user_id = ?", companyId, userId);
        for (Long positionId : positionIds) {
            jdbcTemplate.update("""
                    INSERT INTO sys_user_position
                        (company_id, user_id, position_id, is_primary, status, created_by)
                    VALUES (?, ?, ?, ?, 'ENABLED', ?)
                    """, companyId, userId, positionId,
                    positionId.equals(body.primaryPositionId()) ? 1 : 0, actor.id());
        }
        jdbcTemplate.update("UPDATE sys_user SET auth_version = auth_version + 1 WHERE id = ?", userId);
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "USER", "ASSIGN_POSITION", userId);
    }

    @Transactional
    public void resetPassword(ManagementUser actor, long userId, ResetPassword body) {
        requireManager(actor, "system:user:reset-password");
        long companyId = userCompany(userId);
        resolveCompany(actor, companyId);
        validatePassword(body.newPassword());
        jdbcTemplate.update("""
                UPDATE sys_user
                SET password_hash = ?, must_change_password = 1, password_changed_at = NOW(),
                    auth_version = auth_version + 1, version = version + 1, updated_by = ?
                WHERE id = ? AND user_type = 'COMPANY_USER' AND deleted_at IS NULL
                """, passwordEncoder.encode(body.newPassword()), actor.id(), userId);
        audit(actor, companyId, "USER", "RESET_PASSWORD", userId);
    }

    public List<Map<String, Object>> listPermissions(ManagementUser actor) {
        requireManager(actor, "system:position:permission");
        return jdbcTemplate.queryForList("""
                SELECT id, parent_id AS parentId, permission_code AS permissionCode,
                       permission_name AS permissionName, permission_kind AS permissionKind,
                       module_code AS moduleCode, status
                FROM sys_permission WHERE deleted_at IS NULL AND status = 'ENABLED'
                ORDER BY module_code, id
                """);
    }

    public List<Long> positionPermissions(ManagementUser actor, long positionId) {
        long companyId = positionCompany(positionId);
        resolveCompany(actor, companyId);
        return jdbcTemplate.queryForList(
                "SELECT permission_id FROM sys_position_permission WHERE company_id = ? AND position_id = ? ORDER BY permission_id",
                Long.class, companyId, positionId);
    }

    @Transactional
    public void replacePositionPermissions(ManagementUser actor, long positionId, PositionPermissions body) {
        requireManager(actor, "system:position:permission");
        long companyId = positionCompany(positionId);
        resolveCompany(actor, companyId);
        List<Long> permissionIds = body.permissionIds() == null ? List.of() : body.permissionIds().stream().distinct().toList();
        for (Long permissionId : permissionIds) {
            if (count("SELECT COUNT(*) FROM sys_permission WHERE id = ? AND status = 'ENABLED' AND deleted_at IS NULL", permissionId) == 0) {
                throw badRequest("权限节点不存在或已停用");
            }
        }
        jdbcTemplate.update("DELETE FROM sys_position_permission WHERE company_id = ? AND position_id = ?", companyId, positionId);
        for (Long permissionId : permissionIds) {
            jdbcTemplate.update("""
                    INSERT INTO sys_position_permission (company_id, position_id, permission_id, created_by)
                    VALUES (?, ?, ?, ?)
                    """, companyId, positionId, permissionId, actor.id());
        }
        incrementCompanyAuthVersion(companyId);
        audit(actor, companyId, "POSITION", "SET_PERMISSION", positionId);
    }

    public List<Map<String, Object>> listUserPositions(ManagementUser actor, long userId) {
        long companyId = userCompany(userId);
        resolveCompany(actor, companyId);
        return jdbcTemplate.queryForList("""
                SELECT up.position_id AS positionId, p.position_code AS positionCode,
                       p.position_name AS positionName, p.org_unit_id AS orgUnitId,
                       o.org_name AS orgName, up.is_primary AS primaryPosition
                FROM sys_user_position up
                JOIN sys_position p ON p.id = up.position_id AND p.deleted_at IS NULL
                JOIN sys_org_unit o ON o.id = p.org_unit_id AND o.deleted_at IS NULL
                WHERE up.company_id = ? AND up.user_id = ?
                  AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                ORDER BY up.is_primary DESC, p.position_name
                """, companyId, userId);
    }

    private void moveOrganization(long companyId, long id, long newParentId) {
        Map<String, Object> org = organization(id);
        ParentOrg newParent = parentOrganization(companyId, newParentId);
        String oldPath = (String) org.get("orgPath");
        if (newParent.path().startsWith(oldPath)) {
            throw badRequest("不能将组织移动到自身或下级组织中");
        }
        int oldLevel = ((Number) org.get("orgLevel")).intValue();
        int delta = newParent.level() + 1 - oldLevel;
        String newPath = newParent.path() + id + "/";
        List<Map<String, Object>> descendants = jdbcTemplate.queryForList("""
                SELECT id, org_path AS orgPath, org_level AS orgLevel
                FROM sys_org_unit
                WHERE company_id = ? AND org_path LIKE ? AND deleted_at IS NULL
                ORDER BY org_level
                """, companyId, oldPath + "%");
        for (Map<String, Object> descendant : descendants) {
            long descendantId = ((Number) descendant.get("id")).longValue();
            String descendantPath = (String) descendant.get("orgPath");
            int descendantLevel = ((Number) descendant.get("orgLevel")).intValue();
            String replacedPath = newPath + descendantPath.substring(oldPath.length());
            jdbcTemplate.update("UPDATE sys_org_unit SET org_path = ?, org_level = ?, updated_at = NOW() WHERE id = ?",
                    replacedPath, descendantLevel + delta, descendantId);
        }
        jdbcTemplate.update("UPDATE sys_org_unit SET parent_id = ? WHERE id = ?", newParentId, id);
    }

    private ParentOrg parentOrganization(long companyId, Long parentId) {
        if (parentId == null) {
            List<ParentOrg> roots = jdbcTemplate.query("""
                    SELECT id, org_path, org_level, status FROM sys_org_unit
                    WHERE company_id = ? AND parent_id IS NULL AND deleted_at IS NULL
                    """, (rs, rowNum) -> new ParentOrg(rs.getLong("id"), rs.getString("org_path"),
                    rs.getInt("org_level"), rs.getString("status")), companyId);
            if (roots.isEmpty()) {
                throw badRequest("公司根组织不存在");
            }
            return roots.get(0);
        }
        List<ParentOrg> values = jdbcTemplate.query("""
                SELECT id, org_path, org_level, status FROM sys_org_unit
                WHERE id = ? AND company_id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> new ParentOrg(rs.getLong("id"), rs.getString("org_path"),
                rs.getInt("org_level"), rs.getString("status")), parentId, companyId);
        if (values.isEmpty()) {
            throw badRequest("上级组织不存在或不属于当前公司");
        }
        return values.get(0);
    }

    private Map<String, Object> organization(long id) {
        List<Map<String, Object>> values = jdbcTemplate.queryForList("""
                SELECT id, company_id AS companyId, parent_id AS parentId,
                       org_path AS orgPath, org_level AS orgLevel
                FROM sys_org_unit WHERE id = ? AND deleted_at IS NULL
                """, id);
        if (values.isEmpty()) {
            throw notFound("组织不存在");
        }
        return values.get(0);
    }

    private void organizationInCompany(long companyId, Long orgUnitId) {
        if (orgUnitId == null || count("""
                SELECT COUNT(*) FROM sys_org_unit
                WHERE id = ? AND company_id = ? AND status = 'ENABLED' AND deleted_at IS NULL
                """, orgUnitId, companyId) == 0) {
            throw badRequest("组织不存在、已停用或不属于当前公司");
        }
    }

    private long positionCompany(long id) {
        List<Long> values = jdbcTemplate.query("SELECT company_id FROM sys_position WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getLong(1), id);
        if (values.isEmpty()) {
            throw notFound("岗位不存在");
        }
        return values.get(0);
    }

    private long userCompany(long id) {
        List<Long> values = jdbcTemplate.query("""
                SELECT company_id FROM sys_user_company
                WHERE user_id = ? AND status = 'ENABLED' AND deleted_at IS NULL ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), id);
        if (values.isEmpty()) {
            throw notFound("用户不属于任何有效公司");
        }
        return values.get(0);
    }

    private boolean isCompanyAdmin(long userId, long companyId) {
        return count("""
                SELECT COUNT(*) FROM sys_user_company
                WHERE user_id = ? AND company_id = ? AND is_company_admin = 1
                  AND status = 'ENABLED' AND deleted_at IS NULL
                """, userId, companyId) > 0;
    }

    private void requireAnotherCompanyAdmin(long companyId, long excludedUserId) {
        if (count("""
                SELECT COUNT(*) FROM sys_user_company uc
                JOIN sys_user u ON u.id = uc.user_id AND u.status = 'ENABLED' AND u.deleted_at IS NULL
                WHERE uc.company_id = ? AND uc.user_id <> ? AND uc.is_company_admin = 1
                  AND uc.status = 'ENABLED' AND uc.deleted_at IS NULL
                """, companyId, excludedUserId) == 0) {
            throw badRequest("每家公司必须至少保留一个启用的公司管理员");
        }
    }

    private void createRootOrganization(long companyId, String companyCode, String companyName, long actorId) {
        long id = insertAndReturnId("""
                INSERT INTO sys_org_unit
                    (company_id, parent_id, org_code, org_name, org_path, org_level, status, created_by)
                VALUES (?, NULL, ?, ?, '/', 1, 'ENABLED', ?)
                """, companyId, "ORG-" + companyCode + "-ROOT", companyName, actorId);
        jdbcTemplate.update("UPDATE sys_org_unit SET org_path = ? WHERE id = ?", "/" + id + "/", id);
    }

    private long insertAndReturnId(String sql, Object... args) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        if (keyHolder.getKey() == null) {
            throw new IllegalStateException("数据库未返回新增记录ID");
        }
        return keyHolder.getKey().longValue();
    }

    private long resolveCompany(ManagementUser actor, Long requestedCompanyId) {
        if (isPlatform(actor)) {
            if (requestedCompanyId == null) {
                throw badRequest("平台管理员必须指定公司");
            }
            if (count("SELECT COUNT(*) FROM sys_company WHERE id = ? AND deleted_at IS NULL", requestedCompanyId) == 0) {
                throw notFound("公司不存在");
            }
            return requestedCompanyId;
        }
        if (actor.companyId() == null) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "当前账号没有有效公司");
        }
        if (requestedCompanyId != null && !actor.companyId().equals(requestedCompanyId)) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "不能访问其他公司的数据");
        }
        return actor.companyId();
    }

    private void requireManager(ManagementUser actor, String permission) {
        if (isPlatform(actor) || actor.roles().contains("COMPANY_ADMIN")
                || actor.permissions().contains("*") || actor.permissions().contains(permission)) {
            return;
        }
        throw new ManagementException(HttpStatus.FORBIDDEN, "没有执行该操作的权限");
    }

    private void requirePlatform(ManagementUser actor) {
        if (!isPlatform(actor)) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "仅平台管理员可以执行该操作");
        }
    }

    private boolean isPlatform(ManagementUser actor) {
        return actor.roles().contains("PLATFORM_ADMIN");
    }

    private void incrementCompanyAuthVersion(long companyId) {
        jdbcTemplate.update("UPDATE sys_company SET auth_version = auth_version + 1 WHERE id = ?", companyId);
    }

    private String companyCode(long companyId) {
        String code = jdbcTemplate.queryForObject(
                "SELECT company_code FROM sys_company WHERE id = ? AND deleted_at IS NULL", String.class, companyId);
        if (code == null) {
            throw notFound("公司不存在");
        }
        return code;
    }

    private void audit(ManagementUser actor, Long companyId, String targetType, String operation, long targetId) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log
                    (company_id, user_id, username, module_code, operation_type, target_type, target_id)
                VALUES (?, ?, ?, 'SYSTEM', ?, ?, ?)
                """, companyId, actor.id(), actor.username(), operation, targetType, String.valueOf(targetId));
    }

    private int count(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private void requireUpdated(int changed) {
        if (changed == 0) {
            throw new ManagementException(HttpStatus.CONFLICT, "数据已被其他用户修改，请刷新后重试");
        }
    }

    private String normalizeCode(String value) {
        String code = required(value, "公司编号").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{2,32}")) {
            throw badRequest("公司编号只能包含2至32位大写字母、数字、下划线或中划线");
        }
        return code;
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw badRequest(label + "不能为空");
        }
        return value.trim();
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    String normalizeContactPhone(String value) {
        String phone = trim(value);
        if (phone == null) {
            return null;
        }
        int digitCount = 0;
        int parenthesisBalance = 0;
        boolean validParentheses = true;
        for (int index = 0; index < phone.length(); index++) {
            char character = phone.charAt(index);
            if (character >= '0' && character <= '9') {
                digitCount++;
            } else if (character == '(') {
                parenthesisBalance++;
                validParentheses &= parenthesisBalance == 1;
            } else if (character == ')') {
                parenthesisBalance--;
                validParentheses &= parenthesisBalance >= 0;
            }
        }
        int plusIndex = phone.indexOf('+');
        boolean validPlus = plusIndex < 0 || (plusIndex == 0 && phone.lastIndexOf('+') == 0);
        char firstCharacter = phone.charAt(0);
        char lastCharacter = phone.charAt(phone.length() - 1);
        boolean validBoundary = firstCharacter == '+' || firstCharacter == '('
                || (firstCharacter >= '0' && firstCharacter <= '9');
        validBoundary &= lastCharacter == ')'
                || (lastCharacter >= '0' && lastCharacter <= '9');
        if (phone.length() < 5 || phone.length() > 32
                || !CONTACT_PHONE_CHARACTERS.matcher(phone).matches()
                || digitCount < 5 || digitCount > 20
                || !validPlus || !validBoundary || !validParentheses || parenthesisBalance != 0) {
            throw badRequest("联系电话格式不正确，可填写国际号码、座机或手机号");
        }
        return phone;
    }

    private String status(String value) {
        String normalized = value == null || value.isBlank() ? "ENABLED" : value.toUpperCase(Locale.ROOT);
        if (!List.of("ENABLED", "DISABLED").contains(normalized)) {
            throw badRequest("状态只能为ENABLED或DISABLED");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw badRequest("密码长度必须为8至72位");
        }
    }

    private ManagementException badRequest(String message) {
        return new ManagementException(HttpStatus.BAD_REQUEST, message);
    }

    private ManagementException notFound(String message) {
        return new ManagementException(HttpStatus.NOT_FOUND, message);
    }

    private record ParentOrg(long id, String path, int level, String status) {
    }
}
