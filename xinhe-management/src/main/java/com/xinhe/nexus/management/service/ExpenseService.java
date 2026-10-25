package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExpenseService {
    private static final List<String> CATEGORIES = List.of("ACCOMMODATION", "MEAL", "TRANSPORT", "OTHER");
    private static final DateTimeFormatter CLAIM_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final Path uploadRoot;

    public ExpenseService(JdbcTemplate jdbcTemplate,
                          @Value("${xinhe.expense.upload-dir:/data/uploads/expense}") String uploadDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadRoot = Path.of(uploadDir);
    }

    public List<Map<String, Object>> listMine(ManagementUser user) {
        requireCompany(user);
        return jdbcTemplate.queryForList(
                """
                SELECT id, claim_no AS claimNo, total_amount AS totalAmount, status,
                       current_reviewer_id AS currentReviewerId, submitted_at AS submittedAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM oa_expense_claim
                WHERE company_id = ? AND applicant_user_id = ? AND deleted_at IS NULL
                ORDER BY updated_at DESC, id DESC
                """, user.companyId(), user.id());
    }

    public Map<String, Object> detail(ManagementUser user, long claimId) {
        Map<String, Object> claim = claimReadable(user, claimId);
        claim.put("items", jdbcTemplate.queryForList(
                """
                SELECT id, expense_category AS expenseCategory, expense_date AS expenseDate,
                       expense_location AS expenseLocation, amount, remark
                FROM oa_expense_item WHERE claim_id = ? ORDER BY id
                """, claimId));
        List<Map<String, Object>> attachments = jdbcTemplate.queryForList(
                """
                SELECT id, item_id AS itemId, attachment_kind AS attachmentKind, file_name AS fileName,
                       content_type AS contentType, file_size AS fileSize, created_at AS createdAt
                FROM oa_expense_attachment WHERE claim_id = ? AND deleted_at IS NULL ORDER BY id
                """, claimId);
        claim.put("attachments", attachments);
        List<Map<String, Object>> approvalNodes = jdbcTemplate.queryForList(
                """
                SELECT n.id, n.sequence_no AS sequenceNo, n.org_unit_id AS orgUnitId,
                       o.org_name AS orgName, n.reviewer_user_id AS reviewerUserId,
                       u.display_name AS reviewerName, n.status, n.opinion, n.acted_at AS actedAt
                FROM oa_expense_approval_node n
                LEFT JOIN sys_org_unit o ON o.id = n.org_unit_id
                LEFT JOIN sys_user u ON u.id = n.reviewer_user_id
                WHERE n.claim_id = ? ORDER BY n.sequence_no
                """, claimId);
        if (approvalNodes.isEmpty() && ("DRAFT".equals(claim.get("status")) || "REJECTED".equals(claim.get("status")))) {
            approvalNodes = approvalPreview(((Number) claim.get("companyId")).longValue(), ((Number) claim.get("applicantUserId")).longValue());
        }
        claim.put("approvalNodes", approvalNodes);
        return claim;
    }

    @Transactional
    public Map<String, Object> create(ManagementUser user) {
        requireCompany(user);
        jdbcTemplate.update(
                "INSERT INTO oa_expense_claim (claim_no, company_id, applicant_user_id, total_amount) VALUES (?, ?, ?, 0)",
                UUID.randomUUID().toString().replace("-", ""), user.companyId(), user.id());
        long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String claimNo = "BX-" + LocalDateTime.now().format(CLAIM_NO_TIME) + String.format("%04d", id % 10000);
        jdbcTemplate.update("UPDATE oa_expense_claim SET claim_no = ? WHERE id = ?", claimNo, id);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> save(ManagementUser user, long claimId, SaveRequest request) {
        Map<String, Object> claim = claim(user, claimId);
        ensureEditable(claim);
        List<ItemRequest> items = request.items() == null ? List.of() : request.items();
        for (ItemRequest item : items) {
            validateCategory(item.expenseCategory());
            if (item.amount() == null || item.amount().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("费用金额不能小于0");
            }
        }
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList("SELECT id FROM oa_expense_item WHERE claim_id = ?", claimId);
        java.util.Set<Long> submittedIds = new java.util.HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItemRequest item : items) {
            if (item.id() != null) {
                Number owned = (Number) jdbcTemplate.queryForMap("SELECT COUNT(*) AS count FROM oa_expense_item WHERE id = ? AND claim_id = ?", item.id(), claimId).get("count");
                if (owned == null || owned.intValue() == 0) throw new IllegalArgumentException("费用明细不存在");
                submittedIds.add(item.id());
                jdbcTemplate.update("UPDATE oa_expense_item SET expense_category = ?, expense_date = ?, expense_location = ?, amount = ?, remark = ? WHERE id = ? AND claim_id = ?",
                        item.expenseCategory(), item.expenseDate(), trim(item.expenseLocation()), item.amount(), trim(item.remark()), item.id(), claimId);
            } else {
                jdbcTemplate.update("INSERT INTO oa_expense_item (claim_id, expense_category, expense_date, expense_location, amount, remark) VALUES (?, ?, ?, ?, ?, ?)",
                        claimId, item.expenseCategory(), item.expenseDate(), trim(item.expenseLocation()), item.amount(), trim(item.remark()));
            }
            total = total.add(item.amount() == null ? BigDecimal.ZERO : item.amount());
        }
        for (Map<String, Object> existing : existingRows) {
            long existingId = ((Number) existing.get("id")).longValue();
            if (!submittedIds.contains(existingId) && items.stream().noneMatch(item -> item.id() != null && item.id() == existingId)) {
                jdbcTemplate.update("UPDATE oa_expense_attachment SET deleted_at = NOW() WHERE item_id = ? AND claim_id = ? AND deleted_at IS NULL", existingId, claimId);
                jdbcTemplate.update("DELETE FROM oa_expense_item WHERE id = ? AND claim_id = ?", existingId, claimId);
            }
        }
        jdbcTemplate.update("UPDATE oa_expense_claim SET total_amount = ?, version = version + 1 WHERE id = ?", total, claimId);
        return detail(user, claimId);
    }

    @Transactional
    public void submit(ManagementUser user, long claimId) {
        Map<String, Object> claim = claim(user, claimId);
        ensureEditable(claim);
        Number itemCount = (Number) jdbcTemplate.queryForMap("SELECT COUNT(*) AS count FROM oa_expense_item WHERE claim_id = ?", claimId).get("count");
        if (itemCount == null || itemCount.longValue() == 0) throw new IllegalArgumentException("至少填写一条费用明细");
        Number invalid = (Number) jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS count FROM oa_expense_item WHERE claim_id = ? AND (expense_date IS NULL OR expense_location IS NULL OR amount <= 0)",
                claimId).get("count");
        if (invalid != null && invalid.longValue() > 0) throw new IllegalArgumentException("费用明细的日期、地点和金额必须填写完整");
        List<Map<String, Object>> route = approvalPreview(user.companyId(), user.id());
        if (route.isEmpty()) throw new IllegalArgumentException("未找到可用的部门负责人审核流程");
        Map<String, Object> unconfigured = route.stream().filter(node -> node.get("reviewerUserId") == null).findFirst().orElse(null);
        if (unconfigured != null) throw new IllegalArgumentException("审核流程未配置完整：" + unconfigured.get("orgName") + "缺少负责人");
        jdbcTemplate.update("DELETE FROM oa_expense_approval_node WHERE claim_id = ?", claimId);
        int sequence = 1;
        for (Map<String, Object> node : route) {
            jdbcTemplate.update("INSERT INTO oa_expense_approval_node (claim_id, sequence_no, org_unit_id, reviewer_user_id, reviewer_position_id) VALUES (?, ?, ?, ?, ?)",
                    claimId, sequence++, node.get("orgUnitId"), node.get("reviewerUserId"), node.get("reviewerPositionId"));
        }
        Long firstReviewer = jdbcTemplate.query(
                "SELECT reviewer_user_id FROM oa_expense_approval_node WHERE claim_id = ? ORDER BY sequence_no LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, claimId);
        jdbcTemplate.update("UPDATE oa_expense_claim SET status = 'PENDING', current_reviewer_id = ?, submitted_at = NOW(), rejected_at = NULL, version = version + 1 WHERE id = ?", firstReviewer, claimId);
    }

    @Transactional
    public void delete(ManagementUser user, long claimId) {
        Map<String, Object> claim = claim(user, claimId);
        ensureDeletable(claim);
        jdbcTemplate.update("UPDATE oa_expense_attachment SET deleted_at = NOW() WHERE claim_id = ? AND deleted_at IS NULL", claimId);
        jdbcTemplate.update("DELETE FROM oa_expense_approval_node WHERE claim_id = ?", claimId);
        jdbcTemplate.update("UPDATE oa_expense_claim SET deleted_at = NOW(), version = version + 1 WHERE id = ?", claimId);
    }

    @Transactional
    public void review(ManagementUser user, long claimId, String action, String opinion) {
        requireCompany(user);
        Map<String, Object> claim = jdbcTemplate.queryForMap("SELECT * FROM oa_expense_claim WHERE id = ? AND company_id = ? AND deleted_at IS NULL", claimId, user.companyId());
        Long currentReviewer = claim.get("current_reviewer_id") == null ? null : ((Number) claim.get("current_reviewer_id")).longValue();
        if (currentReviewer == null || currentReviewer != user.id()) throw new ManagementException(HttpStatus.FORBIDDEN, "当前报销单不在你的审核节点");
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) throw new IllegalArgumentException("审核动作不正确");
        if ("REJECT".equals(action) && (opinion == null || opinion.isBlank())) throw new IllegalArgumentException("驳回时必须填写原因");
        Long nodeId = jdbcTemplate.queryForObject("SELECT id FROM oa_expense_approval_node WHERE claim_id = ? AND reviewer_user_id = ? AND status = 'PENDING' ORDER BY sequence_no LIMIT 1", Long.class, claimId, user.id());
        jdbcTemplate.update("UPDATE oa_expense_approval_node SET status = ?, opinion = ?, acted_at = NOW() WHERE id = ?", "APPROVE".equals(action) ? "APPROVED" : "REJECTED", trim(opinion), nodeId);
        if ("REJECT".equals(action)) {
            jdbcTemplate.update("UPDATE oa_expense_claim SET status = 'REJECTED', current_reviewer_id = NULL, rejected_at = NOW(), first_reviewed_at = COALESCE(first_reviewed_at, NOW()), version = version + 1 WHERE id = ?", claimId);
            return;
        }
        Long nextReviewer = jdbcTemplate.query("SELECT reviewer_user_id FROM oa_expense_approval_node WHERE claim_id = ? AND status = 'PENDING' ORDER BY sequence_no LIMIT 1", rs -> rs.next() ? rs.getLong(1) : null, claimId);
        if (nextReviewer == null) {
            jdbcTemplate.update("UPDATE oa_expense_claim SET status = 'APPROVED', current_reviewer_id = NULL, approved_at = NOW(), first_reviewed_at = COALESCE(first_reviewed_at, NOW()), version = version + 1 WHERE id = ?", claimId);
        } else {
            jdbcTemplate.update("UPDATE oa_expense_claim SET status = 'REVIEWING', current_reviewer_id = ?, first_reviewed_at = COALESCE(first_reviewed_at, NOW()), version = version + 1 WHERE id = ?", nextReviewer, claimId);
        }
    }

    public long upload(ManagementUser user, long claimId, long itemId, String kind, MultipartFile file) {
        Map<String, Object> claim = claim(user, claimId);
        ensureEditable(claim);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        if (file.getSize() > 20 * 1024 * 1024) throw new IllegalArgumentException("文件不能超过20MB");
        if (!"INVOICE".equals(kind) && !"OTHER".equals(kind)) throw new IllegalArgumentException("附件类型不正确");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if ("INVOICE".equals(kind) && !"application/pdf".equals(type)) throw new IllegalArgumentException("发票只允许上传PDF文件");
        if ("OTHER".equals(kind) && !List.of("application/pdf", "image/jpeg", "image/png").contains(type)) throw new IllegalArgumentException("其他附件仅支持PDF、JPG和PNG");
        Number itemExists = (Number) jdbcTemplate.queryForMap("SELECT COUNT(*) AS count FROM oa_expense_item WHERE id = ? AND claim_id = ?", itemId, claimId).get("count");
        if (itemExists == null || itemExists.intValue() == 0) throw new IllegalArgumentException("费用明细不存在");
        String extension = extension(file.getOriginalFilename());
        String key = "company/" + user.companyId() + "/expense/" + LocalDate.now() + "/" + UUID.randomUUID() + extension;
        Path target = uploadRoot.resolve(key).normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalArgumentException("附件保存失败");
        }
        jdbcTemplate.update("INSERT INTO oa_expense_attachment (claim_id, item_id, company_id, attachment_kind, file_name, object_key, content_type, file_size, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", claimId, itemId, user.companyId(), kind, file.getOriginalFilename(), key, type, file.getSize(), user.id());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public FileSystemResource resource(ManagementUser user, long attachmentId) {
        requireCompany(user);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT object_key FROM oa_expense_attachment WHERE id = ? AND company_id = ? AND deleted_at IS NULL", attachmentId, user.companyId());
        Path path = uploadRoot.resolve((String) row.get("object_key")).normalize();
        if (!path.startsWith(uploadRoot) || !Files.exists(path)) throw new ManagementException(HttpStatus.NOT_FOUND, "附件不存在");
        return new FileSystemResource(path);
    }

    public String contentType(ManagementUser user, long attachmentId) {
        requireCompany(user);
        return jdbcTemplate.queryForObject("SELECT content_type FROM oa_expense_attachment WHERE id = ? AND company_id = ? AND deleted_at IS NULL", String.class, attachmentId, user.companyId());
    }

    private Map<String, Object> claim(ManagementUser user, long claimId) {
        requireCompany(user);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, claim_no AS claimNo, company_id AS companyId, applicant_user_id AS applicantUserId, total_amount AS totalAmount, status, current_reviewer_id AS currentReviewerId, submitted_at AS submittedAt, created_at AS createdAt, updated_at AS updatedAt FROM oa_expense_claim WHERE id = ? AND company_id = ? AND applicant_user_id = ? AND deleted_at IS NULL", claimId, user.companyId(), user.id());
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "报销单不存在");
        return new java.util.HashMap<>(rows.get(0));
    }

    private Map<String, Object> claimReadable(ManagementUser user, long claimId) {
        requireCompany(user);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, claim_no AS claimNo, company_id AS companyId, applicant_user_id AS applicantUserId, total_amount AS totalAmount, status, current_reviewer_id AS currentReviewerId, submitted_at AS submittedAt, created_at AS createdAt, updated_at AS updatedAt FROM oa_expense_claim WHERE id = ? AND company_id = ? AND deleted_at IS NULL",
                claimId, user.companyId());
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "报销单不存在");
        Map<String, Object> row = rows.get(0);
        long applicant = ((Number) row.get("applicantUserId")).longValue();
        long currentReviewer = row.get("currentReviewerId") == null ? -1 : ((Number) row.get("currentReviewerId")).longValue();
        if (applicant != user.id() && currentReviewer != user.id()
                && !user.roles().contains("COMPANY_ADMIN") && !user.roles().contains("PLATFORM_ADMIN")) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "无权查看该报销单");
        }
        return new java.util.HashMap<>(row);
    }

    private void ensureEditable(Map<String, Object> claim) {
        if (!"DRAFT".equals(claim.get("status")) && !"REJECTED".equals(claim.get("status"))) throw new IllegalArgumentException("当前状态不允许编辑");
    }

    private void ensureDeletable(Map<String, Object> claim) {
        if (!"DRAFT".equals(claim.get("status")) && !"REJECTED".equals(claim.get("status"))) throw new IllegalArgumentException("只有草稿或已驳回的报销单可以删除");
    }

    private void requireCompany(ManagementUser user) {
        if (user == null || user.companyId() == null) throw new ManagementException(HttpStatus.FORBIDDEN, "平台账号不能操作个人报销");
    }

    private void validateCategory(String category) {
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("费用类型不正确");
    }

    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String extension(String name) { if (name == null || !name.contains(".")) return ""; return name.substring(name.lastIndexOf('.')).toLowerCase(); }

    private List<Map<String, Object>> approvalPreview(long companyId, long applicantUserId) {
        Long currentOrg = jdbcTemplate.query("SELECT p.org_unit_id FROM sys_user_position up JOIN sys_position p ON p.id = up.position_id AND p.status = 'ENABLED' AND p.deleted_at IS NULL WHERE up.user_id = ? AND up.company_id = ? AND up.status = 'ENABLED' AND up.deleted_at IS NULL ORDER BY up.id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, applicantUserId, companyId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        int sequence = 1;
        while (currentOrg != null) {
            List<Map<String, Object>> orgRows = jdbcTemplate.queryForList("SELECT parent_id AS parentId, org_name AS orgName, manager_position_id AS managerPositionId FROM sys_org_unit WHERE id = ? AND company_id = ? AND deleted_at IS NULL", currentOrg, companyId);
            if (orgRows.isEmpty()) break;
            Map<String, Object> org = orgRows.get(0);
            Long positionId = org.get("managerPositionId") == null ? null : ((Number) org.get("managerPositionId")).longValue();
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("id", -sequence);
            node.put("sequenceNo", sequence++);
            node.put("orgUnitId", currentOrg);
            node.put("orgName", org.get("orgName"));
            node.put("reviewerPositionId", positionId);
            node.put("status", "PENDING");
            node.put("preview", true);
            if (positionId == null) {
                node.put("reviewerName", "待配置负责人");
            } else {
                List<Map<String, Object>> reviewers = jdbcTemplate.queryForList("SELECT up.user_id AS userId, u.display_name AS reviewerName FROM sys_user_position up JOIN sys_user u ON u.id = up.user_id AND u.status = 'ENABLED' AND u.deleted_at IS NULL WHERE up.company_id = ? AND up.position_id = ? AND up.status = 'ENABLED' AND up.deleted_at IS NULL ORDER BY up.id LIMIT 1", companyId, positionId);
                if (reviewers.isEmpty()) node.put("reviewerName", "待配置负责人");
                else { node.put("reviewerUserId", reviewers.get(0).get("userId")); node.put("reviewerName", reviewers.get(0).get("reviewerName")); }
            }
            nodes.add(node);
            currentOrg = org.get("parentId") == null ? null : ((Number) org.get("parentId")).longValue();
        }
        return nodes;
    }

    public record SaveRequest(List<ItemRequest> items) {}
    public record ItemRequest(Long id, String expenseCategory, LocalDate expenseDate, String expenseLocation, BigDecimal amount, String remark) {}
}
