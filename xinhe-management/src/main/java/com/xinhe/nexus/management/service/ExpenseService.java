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
                       approval_round AS approvalRound, version,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM oa_expense_claim
                WHERE company_id = ? AND applicant_user_id = ? AND deleted_at IS NULL
                ORDER BY updated_at DESC, id DESC
                """, user.companyId(), user.id());
    }

    public List<Map<String, Object>> listPending(ManagementUser user) {
        requireCompany(user);
        boolean finalApprover = canFinalApprove(user);
        return jdbcTemplate.queryForList(
                """
                SELECT c.id, c.claim_no AS claimNo, c.total_amount AS totalAmount, c.status,
                       c.current_reviewer_id AS currentReviewerId, c.submitted_at AS submittedAt,
                       c.approval_round AS approvalRound, c.version,
                       u.display_name AS applicantName, o.org_name AS applicantOrgName,
                       CASE WHEN c.status = 'PENDING' THEN '直属部门负责人审批'
                            ELSE '最终审批' END AS currentNodeName
                FROM oa_expense_claim c
                JOIN sys_user u ON u.id = c.applicant_user_id
                LEFT JOIN sys_org_unit o ON o.id = c.applicant_org_id
                WHERE c.company_id = ? AND c.deleted_at IS NULL
                  AND c.applicant_user_id <> ?
                  AND ((c.status = 'PENDING' AND c.current_reviewer_id = ?)
                       OR (c.status = 'REVIEWING' AND ? = 1))
                ORDER BY c.submitted_at, c.id
                """, user.companyId(), user.id(), user.id(), finalApprover ? 1 : 0);
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
                SELECT n.id, n.approval_round AS approvalRound, n.sequence_no AS sequenceNo,
                       n.node_type AS nodeType, n.org_unit_id AS orgUnitId,
                       COALESCE(n.org_name_snapshot, o.org_name) AS orgName,
                       n.reviewer_user_id AS reviewerUserId,
                       COALESCE(n.reviewer_name_snapshot, u.display_name) AS reviewerName,
                       n.reviewer_position_name_snapshot AS reviewerPositionName,
                       n.status, n.opinion, n.acted_at AS actedAt
                FROM oa_expense_approval_node n
                LEFT JOIN sys_org_unit o ON o.id = n.org_unit_id
                LEFT JOIN sys_user u ON u.id = n.reviewer_user_id
                WHERE n.claim_id = ? ORDER BY n.approval_round, n.sequence_no
                """, claimId);
        if (approvalNodes.isEmpty() && ("DRAFT".equals(claim.get("status")) || "REJECTED".equals(claim.get("status")))) {
            approvalNodes = approvalPreview(((Number) claim.get("companyId")).longValue(), ((Number) claim.get("applicantUserId")).longValue());
        }
        claim.put("approvalNodes", approvalNodes);
        claim.put("canReview", canReview(user, claim));
        claim.put("canWithdraw", canWithdraw(user, claim));
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
        int updated = jdbcTemplate.update(
                "UPDATE oa_expense_claim SET total_amount = ?, version = version + 1 " +
                        "WHERE id = ? AND applicant_user_id = ? AND status IN ('DRAFT', 'REJECTED')",
                total, claimId, user.id());
        if (updated != 1) throw new IllegalArgumentException("当前状态不允许编辑");
        return detail(user, claimId);
    }

    @Transactional
    public void submit(ManagementUser user, long claimId, int version) {
        Map<String, Object> claim = claim(user, claimId);
        ensureEditable(claim);
        requireVersion(claim, version);
        Number itemCount = (Number) jdbcTemplate.queryForMap("SELECT COUNT(*) AS count FROM oa_expense_item WHERE claim_id = ?", claimId).get("count");
        if (itemCount == null || itemCount.longValue() == 0) throw new IllegalArgumentException("至少填写一条费用明细");
        Number invalid = (Number) jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS count FROM oa_expense_item WHERE claim_id = ? AND (expense_date IS NULL OR expense_location IS NULL OR amount <= 0)",
                claimId).get("count");
        if (invalid != null && invalid.longValue() > 0) throw new IllegalArgumentException("费用明细的日期、地点和金额必须填写完整");
        List<Map<String, Object>> route = approvalPreview(user.companyId(), user.id());
        Map<String, Object> managerNode = route.stream()
                .filter(node -> "DEPARTMENT_MANAGER".equals(node.get("nodeType"))).findFirst().orElse(null);
        if (managerNode == null || managerNode.get("reviewerUserId") == null) {
            throw new IllegalArgumentException("直属部门未配置有效负责人，无法提交报销");
        }
        if (((Number) managerNode.get("reviewerUserId")).longValue() == user.id()) {
            throw new IllegalArgumentException("申请人不能审批自己的报销，请联系管理员调整直属部门负责人");
        }
        List<Map<String, Object>> finalApprovers = finalApprovers(user.companyId(), user.id());
        if (finalApprovers.isEmpty()) {
            throw new IllegalArgumentException("未找到可用的报销最终审批人，请在权限配置中授予“报销最终审批”权限");
        }
        int nextRound = ((Number) claim.get("approvalRound")).intValue() + 1;
        for (Map<String, Object> node : route) {
            jdbcTemplate.update("""
                    INSERT INTO oa_expense_approval_node
                        (claim_id, approval_round, sequence_no, node_type, org_unit_id,
                         org_name_snapshot, reviewer_user_id, reviewer_name_snapshot,
                         reviewer_position_id, reviewer_position_name_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, claimId, nextRound, node.get("sequenceNo"), node.get("nodeType"),
                    node.get("orgUnitId"), node.get("orgName"), node.get("reviewerUserId"),
                    node.get("reviewerName"), node.get("reviewerPositionId"), node.get("reviewerPositionName"));
        }
        int updated = jdbcTemplate.update("""
                UPDATE oa_expense_claim
                SET status = 'PENDING', current_reviewer_id = ?, applicant_org_id = ?,
                    approval_round = ?, submitted_at = NOW(), approved_at = NULL,
                    rejected_at = NULL, version = version + 1
                WHERE id = ? AND version = ? AND status IN ('DRAFT', 'REJECTED')
                """, managerNode.get("reviewerUserId"), managerNode.get("orgUnitId"),
                nextRound, claimId, version);
        if (updated != 1) throw conflict();
    }

    @Transactional
    public void withdraw(ManagementUser user, long claimId, int version) {
        Map<String, Object> claim = claim(user, claimId);
        requireVersion(claim, version);
        if (!"PENDING".equals(claim.get("status"))) {
            throw new IllegalArgumentException("只有尚未发生审批的报销单可以撤回");
        }
        Number acted = (Number) jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS count FROM oa_expense_approval_node WHERE claim_id = ? AND approval_round = ? AND acted_at IS NOT NULL",
                claimId, claim.get("approvalRound")).get("count");
        if (acted != null && acted.longValue() > 0) {
            throw new IllegalArgumentException("报销单已发生审批，不能撤回");
        }
        int updated = jdbcTemplate.update("""
                UPDATE oa_expense_claim
                SET status = 'DRAFT', current_reviewer_id = NULL, submitted_at = NULL,
                    version = version + 1
                WHERE id = ? AND applicant_user_id = ? AND status = 'PENDING' AND version = ?
                """, claimId, user.id(), version);
        if (updated != 1) throw conflict();
        jdbcTemplate.update("""
                UPDATE oa_expense_approval_node SET status = 'CANCELLED'
                WHERE claim_id = ? AND approval_round = ? AND status = 'PENDING'
                """, claimId, claim.get("approvalRound"));
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
    public void review(ManagementUser user, long claimId, String action, String opinion, int version) {
        requireCompany(user);
        Map<String, Object> claim = jdbcTemplate.queryForMap(
                "SELECT * FROM oa_expense_claim WHERE id = ? AND company_id = ? AND deleted_at IS NULL FOR UPDATE",
                claimId, user.companyId());
        requireVersion(claim, version);
        long applicant = ((Number) claim.get("applicant_user_id")).longValue();
        if (applicant == user.id()) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "申请人不能审批自己的报销单");
        }
        String status = (String) claim.get("status");
        Long currentReviewer = claim.get("current_reviewer_id") == null ? null : ((Number) claim.get("current_reviewer_id")).longValue();
        if ("PENDING".equals(status)) {
            if (currentReviewer == null || currentReviewer != user.id()) {
                throw new ManagementException(HttpStatus.FORBIDDEN, "当前报销单不在你的审核节点");
            }
        } else if ("REVIEWING".equals(status)) {
            requireFinalApprovePermission(user);
        } else {
            throw new IllegalArgumentException("当前状态不允许审批");
        }
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) throw new IllegalArgumentException("审核动作不正确");
        if ("REJECT".equals(action) && (opinion == null || opinion.isBlank())) throw new IllegalArgumentException("驳回时必须填写原因");
        String nodeType = "PENDING".equals(status) ? "DEPARTMENT_MANAGER" : "FINAL_APPROVER";
        Long nodeId = jdbcTemplate.queryForObject("""
                SELECT id FROM oa_expense_approval_node
                WHERE claim_id = ? AND approval_round = ? AND node_type = ? AND status = 'PENDING'
                ORDER BY sequence_no LIMIT 1
                """, Long.class, claimId, claim.get("approval_round"), nodeType);
        int nodeUpdated = jdbcTemplate.update("""
                UPDATE oa_expense_approval_node
                SET status = ?, opinion = ?, acted_at = NOW(), reviewer_user_id = ?, reviewer_name_snapshot = ?
                WHERE id = ? AND status = 'PENDING'
                """, "APPROVE".equals(action) ? "APPROVED" : "REJECTED", trim(opinion),
                user.id(), user.name(), nodeId);
        if (nodeUpdated != 1) throw conflict();
        if ("REJECT".equals(action)) {
            int updated = jdbcTemplate.update("""
                    UPDATE oa_expense_claim
                    SET status = 'REJECTED', current_reviewer_id = NULL, rejected_at = NOW(),
                        first_reviewed_at = COALESCE(first_reviewed_at, NOW()), version = version + 1
                    WHERE id = ? AND version = ? AND status = ?
                    """, claimId, version, status);
            if (updated != 1) throw conflict();
            return;
        }
        if ("REVIEWING".equals(status)) {
            int updated = jdbcTemplate.update("""
                    UPDATE oa_expense_claim
                    SET status = 'APPROVED', current_reviewer_id = NULL, approved_at = NOW(),
                        first_reviewed_at = COALESCE(first_reviewed_at, NOW()), version = version + 1
                    WHERE id = ? AND version = ? AND status = 'REVIEWING'
                    """, claimId, version);
            if (updated != 1) throw conflict();
        } else {
            int updated = jdbcTemplate.update("""
                    UPDATE oa_expense_claim
                    SET status = 'REVIEWING', current_reviewer_id = NULL,
                        first_reviewed_at = COALESCE(first_reviewed_at, NOW()), version = version + 1
                    WHERE id = ? AND version = ? AND status = 'PENDING'
                    """, claimId, version);
            if (updated != 1) throw conflict();
        }
    }

    public long upload(ManagementUser user, long claimId, long itemId, String kind, MultipartFile file) {
        Map<String, Object> claim = claim(user, claimId);
        ensureEditable(claim);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        if (file.getSize() > 20 * 1024 * 1024) throw new IllegalArgumentException("文件不能超过20MB");
        if (!"INVOICE".equals(kind) && !"OTHER".equals(kind)) throw new IllegalArgumentException("附件类型不正确");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if ("INVOICE".equals(kind) && !List.of("application/pdf", "image/jpeg", "image/png").contains(type)) {
            throw new IllegalArgumentException("发票仅支持PDF、JPG和PNG");
        }
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
        Map<String, Object> row = readableAttachment(user, attachmentId);
        Path path = uploadRoot.resolve((String) row.get("objectKey")).normalize();
        if (!path.startsWith(uploadRoot) || !Files.exists(path)) throw new ManagementException(HttpStatus.NOT_FOUND, "附件不存在");
        return new FileSystemResource(path);
    }

    public String contentType(ManagementUser user, long attachmentId) {
        return (String) readableAttachment(user, attachmentId).get("contentType");
    }

    private Map<String, Object> readableAttachment(ManagementUser user, long attachmentId) {
        requireCompany(user);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.object_key AS objectKey, a.content_type AS contentType, "
                        + "c.id AS claimId, c.status, c.applicant_user_id AS applicantUserId, "
                        + "c.current_reviewer_id AS currentReviewerId "
                        + "FROM oa_expense_attachment a JOIN oa_expense_claim c ON c.id = a.claim_id "
                        + "AND c.company_id = a.company_id AND c.deleted_at IS NULL "
                        + "WHERE a.id = ? AND a.company_id = ? AND a.deleted_at IS NULL",
                attachmentId, user.companyId());
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "附件不存在");
        Map<String, Object> row = rows.get(0);
        long applicant = ((Number) row.get("applicantUserId")).longValue();
        long reviewer = row.get("currentReviewerId") == null ? -1 : ((Number) row.get("currentReviewerId")).longValue();
        long claimId = ((Number) row.get("claimId")).longValue();
        boolean historicalReviewer = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oa_expense_approval_node WHERE claim_id = ? AND reviewer_user_id = ?",
                Long.class, claimId, user.id()) > 0;
        boolean finalReviewer = "REVIEWING".equals(row.get("status")) && canFinalApprove(user);
        if (applicant != user.id() && reviewer != user.id() && !historicalReviewer && !finalReviewer
                && !user.roles().contains("COMPANY_ADMIN") && !user.roles().contains("PLATFORM_ADMIN")) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "无权查看该附件");
        }
        return row;
    }

    private Map<String, Object> claim(ManagementUser user, long claimId) {
        requireCompany(user);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, claim_no AS claimNo, company_id AS companyId,
                       applicant_user_id AS applicantUserId, applicant_org_id AS applicantOrgId,
                       total_amount AS totalAmount, status,
                       current_reviewer_id AS currentReviewerId, approval_round AS approvalRound,
                       submitted_at AS submittedAt, version,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM oa_expense_claim
                WHERE id = ? AND company_id = ? AND applicant_user_id = ? AND deleted_at IS NULL
                """, claimId, user.companyId(), user.id());
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "报销单不存在");
        return new java.util.HashMap<>(rows.get(0));
    }

    private Map<String, Object> claimReadable(ManagementUser user, long claimId) {
        requireCompany(user);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT c.id, c.claim_no AS claimNo, c.company_id AS companyId,
                       c.applicant_user_id AS applicantUserId, c.applicant_org_id AS applicantOrgId,
                       u.display_name AS applicantName, o.org_name AS applicantOrgName,
                       c.total_amount AS totalAmount, c.status,
                       c.current_reviewer_id AS currentReviewerId, c.approval_round AS approvalRound,
                       c.submitted_at AS submittedAt, c.version,
                       c.created_at AS createdAt, c.updated_at AS updatedAt
                FROM oa_expense_claim c
                JOIN sys_user u ON u.id = c.applicant_user_id
                LEFT JOIN sys_org_unit o ON o.id = c.applicant_org_id
                WHERE c.id = ? AND c.company_id = ? AND c.deleted_at IS NULL
                """,
                claimId, user.companyId());
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "报销单不存在");
        Map<String, Object> row = rows.get(0);
        long applicant = ((Number) row.get("applicantUserId")).longValue();
        long currentReviewer = row.get("currentReviewerId") == null ? -1 : ((Number) row.get("currentReviewerId")).longValue();
        boolean historicalReviewer = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oa_expense_approval_node WHERE claim_id = ? AND reviewer_user_id = ?",
                Long.class, claimId, user.id()) > 0;
        boolean finalReviewer = "REVIEWING".equals(row.get("status")) && canFinalApprove(user);
        if (applicant != user.id() && currentReviewer != user.id() && !historicalReviewer && !finalReviewer
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

    private void requireVersion(Map<String, Object> claim, Integer version) {
        if (version == null || ((Number) claim.get("version")).intValue() != version) throw conflict();
    }

    private ManagementException conflict() {
        return new ManagementException(HttpStatus.CONFLICT, "报销单已被其他人更新，请刷新后重试");
    }

    private boolean canWithdraw(ManagementUser user, Map<String, Object> claim) {
        if (((Number) claim.get("applicantUserId")).longValue() != user.id() || !"PENDING".equals(claim.get("status"))) return false;
        Number acted = (Number) jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS count FROM oa_expense_approval_node WHERE claim_id = ? AND approval_round = ? AND acted_at IS NOT NULL",
                claim.get("id"), claim.get("approvalRound")).get("count");
        return acted == null || acted.longValue() == 0;
    }

    private boolean canReview(ManagementUser user, Map<String, Object> claim) {
        long applicant = ((Number) claim.get("applicantUserId")).longValue();
        if (applicant == user.id()) return false;
        if ("PENDING".equals(claim.get("status"))) {
            return claim.get("currentReviewerId") != null
                    && ((Number) claim.get("currentReviewerId")).longValue() == user.id();
        }
        return "REVIEWING".equals(claim.get("status")) && canFinalApprove(user);
    }

    private boolean canFinalApprove(ManagementUser user) {
        return user.roles().contains("COMPANY_ADMIN") || user.roles().contains("PLATFORM_ADMIN")
                || user.permissions().contains("*") || user.permissions().contains("oa:expense:approve");
    }

    private void requireFinalApprovePermission(ManagementUser user) {
        if (!canFinalApprove(user)) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "当前账号未配置报销最终审批权限");
        }
    }

    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String extension(String name) { if (name == null || !name.contains(".")) return ""; return name.substring(name.lastIndexOf('.')).toLowerCase(); }

    private List<Map<String, Object>> approvalPreview(long companyId, long applicantUserId) {
        Long currentOrg = jdbcTemplate.query("SELECT p.org_unit_id FROM sys_user_position up JOIN sys_position p ON p.id = up.position_id AND p.status = 'ENABLED' AND p.deleted_at IS NULL WHERE up.user_id = ? AND up.company_id = ? AND up.status = 'ENABLED' AND up.deleted_at IS NULL ORDER BY up.id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, applicantUserId, companyId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> manager = new java.util.LinkedHashMap<>();
        manager.put("id", -1);
        manager.put("sequenceNo", 1);
        manager.put("nodeType", "DEPARTMENT_MANAGER");
        manager.put("orgUnitId", currentOrg);
        manager.put("status", "PENDING");
        manager.put("preview", true);
        if (currentOrg == null) {
            manager.put("reviewerName", "待配置直属部门负责人");
        } else {
            List<Map<String, Object>> orgRows = jdbcTemplate.queryForList("""
                    SELECT o.org_name AS orgName, o.manager_position_id AS managerPositionId,
                           p.position_name AS reviewerPositionName
                    FROM sys_org_unit o
                    LEFT JOIN sys_position p ON p.id = o.manager_position_id
                    WHERE o.id = ? AND o.company_id = ? AND o.deleted_at IS NULL
                    """, currentOrg, companyId);
            if (!orgRows.isEmpty()) {
                Map<String, Object> org = orgRows.get(0);
                manager.put("orgName", org.get("orgName"));
                manager.put("reviewerPositionId", org.get("managerPositionId"));
                manager.put("reviewerPositionName", org.get("reviewerPositionName"));
                if (org.get("managerPositionId") != null) {
                    List<Map<String, Object>> reviewers = jdbcTemplate.queryForList("""
                            SELECT up.user_id AS userId, u.display_name AS reviewerName
                            FROM sys_user_position up
                            JOIN sys_user u ON u.id = up.user_id AND u.status = 'ENABLED' AND u.deleted_at IS NULL
                            WHERE up.company_id = ? AND up.position_id = ?
                              AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                            ORDER BY up.id LIMIT 1
                            """, companyId, org.get("managerPositionId"));
                    if (!reviewers.isEmpty()) {
                        manager.put("reviewerUserId", reviewers.get(0).get("userId"));
                        manager.put("reviewerName", reviewers.get(0).get("reviewerName"));
                    }
                }
            }
        }
        if (manager.get("reviewerName") == null) manager.put("reviewerName", "待配置直属部门负责人");
        nodes.add(manager);

        Map<String, Object> finalNode = new java.util.LinkedHashMap<>();
        finalNode.put("id", -2);
        finalNode.put("sequenceNo", 2);
        finalNode.put("nodeType", "FINAL_APPROVER");
        finalNode.put("orgName", "本公司");
        finalNode.put("reviewerName", finalApprovers(companyId, applicantUserId).stream()
                .map(row -> String.valueOf(row.get("reviewerName"))).reduce((left, right) -> left + "、" + right)
                .orElse("待配置报销最终审批人"));
        finalNode.put("reviewerPositionName", "报销最终审批");
        finalNode.put("status", "PENDING");
        finalNode.put("preview", true);
        nodes.add(finalNode);
        return nodes;
    }

    private List<Map<String, Object>> finalApprovers(long companyId, long excludedUserId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT u.id AS userId, u.display_name AS reviewerName
                FROM sys_user u
                JOIN sys_user_company uc ON uc.user_id = u.id AND uc.company_id = ?
                    AND uc.status = 'ENABLED' AND uc.deleted_at IS NULL
                WHERE u.status = 'ENABLED' AND u.deleted_at IS NULL AND u.id <> ?
                  AND (uc.is_company_admin = 1
                       OR EXISTS (
                           SELECT 1 FROM sys_user_permission sup
                           JOIN sys_permission sp ON sp.id = sup.permission_id
                           WHERE sup.company_id = uc.company_id AND sup.user_id = u.id
                             AND sp.permission_code = 'oa:expense:approve'
                             AND sp.status = 'ENABLED' AND sp.deleted_at IS NULL)
                       OR EXISTS (
                           SELECT 1 FROM sys_user_position eup
                           JOIN sys_position_permission spp ON spp.company_id = eup.company_id
                               AND spp.position_id = eup.position_id
                           JOIN sys_permission pp ON pp.id = spp.permission_id
                           WHERE eup.company_id = uc.company_id AND eup.user_id = u.id
                             AND eup.status = 'ENABLED' AND eup.deleted_at IS NULL
                             AND pp.permission_code = 'oa:expense:approve'
                             AND pp.status = 'ENABLED' AND pp.deleted_at IS NULL))
                ORDER BY u.id
                """, companyId, excludedUserId);
    }

    public record SaveRequest(List<ItemRequest> items) {}
    public record ItemRequest(Long id, String expenseCategory, LocalDate expenseDate, String expenseLocation, BigDecimal amount, String remark) {}
}
