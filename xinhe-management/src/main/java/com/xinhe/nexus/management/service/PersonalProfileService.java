package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class PersonalProfileService {
    private static final List<String> APPROVAL_FIELDS = List.of(
            "gender", "birthDate", "idType", "idNumber", "address",
            "emergencyContactName", "emergencyContactRelation", "emergencyContactPhone", "educations");
    private final JdbcTemplate db;
    private final ObjectMapper objectMapper;
    private final ProfileDataCrypto profileDataCrypto;

    public PersonalProfileService(JdbcTemplate db, ObjectMapper objectMapper, ProfileDataCrypto profileDataCrypto) {
        this.db = db;
        this.objectMapper = objectMapper;
        this.profileDataCrypto = profileDataCrypto;
    }

    public Map<String, Object> myProfile(ManagementUser user) {
        List<Map<String, Object>> rows = db.queryForList(profileSql() + " WHERE ep.user_id=? AND ep.company_id=?", user.id(), user.companyId());
        if (rows.isEmpty()) throw notFound("个人档案不存在");
        return withEducations(rows.get(0), true);
    }

    public Map<String, Object> profile(ManagementUser user, long profileId) {
        requireAdmin(user);
        List<Map<String, Object>> rows = db.queryForList(profileSql() + " WHERE ep.id=? AND ep.company_id=?", profileId, user.companyId());
        if (rows.isEmpty()) throw notFound("人员档案不存在");
        return withEducations(rows.get(0), false);
    }

    @Transactional
    public Map<String, Object> updateBasic(ManagementUser actor, long profileId, String displayName, String phone, String email) {
        Map<String, Object> current = ownProfile(actor, profileId);
        if (displayName == null || displayName.isBlank()) throw bad("姓名不能为空");
        validateEmail(email);
        String name = trim(displayName);
        String normalizedPhone = trim(phone);
        db.update("UPDATE hr_employee_profile SET candidate_name=?,phone=?,email=?,updated_at=NOW() WHERE id=? AND company_id=?", name, normalizedPhone, trim(email), profileId, actor.companyId());
        db.update("UPDATE sys_user SET display_name=?,phone=?,email=?,version=version+1,updated_by=?,updated_at=NOW() WHERE id=? AND deleted_at IS NULL", name, normalizedPhone, trim(email), actor.id(), current.get("userId"));
        Map<String, Object> updated = ownProfile(actor, profileId);
        log(actor, "UPDATE", profileId, current, updated);
        return updated;
    }

    @Transactional
    public Map<String, Object> updateDirect(ManagementUser actor, long profileId, Map<String, Object> input) {
        requireDirectEdit(actor);
        Map<String, Object> current = profile(actor, profileId);
        Map<String, Object> normalized = normalizeDirectFields(input);
        applyProfile(profileId, ((Number) current.get("userId")).longValue(), normalized, actor.id());
        log(actor, "UPDATE", profileId, current, profile(actor, profileId));
        return profile(actor, profileId);
    }

    @Transactional
    public Map<String, Object> submitChange(ManagementUser actor, Map<String, Object> input) {
        Map<String, Object> current = ownProfile(actor, profileId(actor));
        Map<String, Object> normalized = normalizeApprovalFields(input);
        if (normalized.isEmpty()) throw bad("没有可提交的档案变更");
        long approver = findApprover(actor.companyId(), actor.id());
        try {
            long id = insert("INSERT INTO hr_employee_profile_change_request(company_id,profile_id,user_id,applicant_user_id,approver_user_id,before_data,after_data) VALUES(?,?,?,?,?,?,?)",
                    actor.companyId(), current.get("id"), current.get("userId"), actor.id(), approver,
                    json(current), json(normalized));
            log(actor, "SUBMIT", ((Number) current.get("id")).longValue(), current, normalized);
            return Map.of("id", id, "status", "PENDING", "approverUserId", approver);
        } catch (Exception ex) {
            if (ex instanceof ManagementException managementException) throw managementException;
            throw new ManagementException(HttpStatus.INTERNAL_SERVER_ERROR, "档案变更申请提交失败");
        }
    }

    public List<Map<String, Object>> mine(ManagementUser actor) {
        return db.queryForList("SELECT r.id,r.status,r.before_data beforeData,r.after_data afterData,r.review_comment reviewComment,r.created_at createdAt,r.reviewed_at reviewedAt,u.display_name approverName FROM hr_employee_profile_change_request r LEFT JOIN sys_user u ON u.id=r.approver_user_id WHERE r.company_id=? AND r.applicant_user_id=? ORDER BY r.created_at DESC", actor.companyId(), actor.id());
    }

    public List<Map<String, Object>> pending(ManagementUser actor) {
        requireApprove(actor);
        return db.queryForList("SELECT r.id,r.status,r.before_data beforeData,r.after_data afterData,r.created_at createdAt,u.display_name applicantName FROM hr_employee_profile_change_request r JOIN sys_user u ON u.id=r.applicant_user_id WHERE r.company_id=? AND r.approver_user_id=? AND r.status='PENDING' ORDER BY r.created_at ASC", actor.companyId(), actor.id());
    }

    @Transactional
    public Map<String, Object> decide(ManagementUser actor, long id, boolean approve, String comment) {
        requireApprove(actor);
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM hr_employee_profile_change_request WHERE company_id=? AND id=? AND approver_user_id=? AND status='PENDING' FOR UPDATE", actor.companyId(), id, actor.id());
        if (rows.isEmpty()) throw bad("申请不存在、已处理或无审批权限");
        Map<String, Object> request = rows.get(0);
        if (!approve && (comment == null || comment.isBlank())) throw bad("请填写驳回理由");
        if (approve) {
            Map<String, Object> changes = readJson((String) request.get("after_data"));
            applyProfile(((Number) request.get("profile_id")).longValue(), ((Number) request.get("user_id")).longValue(), changes, actor.id());
        }
        db.update("UPDATE hr_employee_profile_change_request SET status=?,review_comment=?,reviewed_by=?,reviewed_at=NOW(),updated_at=NOW() WHERE company_id=? AND id=? AND status='PENDING'", approve ? "APPROVED" : "REJECTED", trim(comment), actor.id(), actor.companyId(), id);
        return Map.of("id", id, "status", approve ? "APPROVED" : "REJECTED");
    }

    private void applyProfile(long profileId, long userId, Map<String, Object> changes, long operatorId) {
        String name = text(changes, "displayName");
        String phone = text(changes, "phone");
        String email = text(changes, "email");
        if (changes.containsKey("gender") || changes.containsKey("birthDate") || changes.containsKey("idType") || changes.containsKey("idNumber") || changes.containsKey("address") || changes.containsKey("emergencyContactName") || changes.containsKey("emergencyContactRelation") || changes.containsKey("emergencyContactPhone")) {
            StringBuilder sql = new StringBuilder("UPDATE hr_employee_profile SET ");
            List<Object> args = new ArrayList<>();
            if (changes.containsKey("gender")) { sql.append("gender=?,"); args.add(text(changes, "gender")); }
            if (changes.containsKey("birthDate")) { sql.append("birth_date=?,"); args.add(date(changes, "birthDate")); }
            if (changes.containsKey("idType")) { sql.append("id_type=?,"); args.add(text(changes, "idType")); }
            if (changes.containsKey("idNumber") && text(changes, "idNumber") != null) { sql.append("id_number_ciphertext=?,id_number_hash=?,"); args.add(profileDataCrypto.encrypt(text(changes, "idNumber"))); args.add(hashNullable(text(changes, "idNumber"))); }
            if (changes.containsKey("address")) { sql.append("address_ciphertext=?,"); args.add(text(changes, "address")); }
            if (changes.containsKey("emergencyContactName")) { sql.append("emergency_contact_name=?,"); args.add(text(changes, "emergencyContactName")); }
            if (changes.containsKey("emergencyContactRelation")) { sql.append("emergency_contact_relation=?,"); args.add(text(changes, "emergencyContactRelation")); }
            if (changes.containsKey("emergencyContactPhone")) { sql.append("emergency_contact_phone=?,"); args.add(text(changes, "emergencyContactPhone")); }
            sql.append("updated_at=NOW() WHERE id=?");
            args.add(profileId);
            db.update(sql.toString(), args.toArray());
        }
        if (changes.containsKey("educations")) {
            db.update("DELETE FROM hr_employee_education WHERE profile_id=?", profileId);
            Object value = changes.get("educations");
            if (value instanceof List<?> list) for (Object item : list) {
                Map<String, Object> education = objectMapper.convertValue(item, new TypeReference<>() {});
                db.update("INSERT INTO hr_employee_education(profile_id,start_month,end_month,education_level,school_name,sort_order) VALUES(?,?,?,?,?,?)", profileId, text(education,"startMonth"), text(education,"endMonth"), text(education,"educationLevel"), text(education,"schoolName"), list.indexOf(item));
            }
        }
        if (name != null || changes.containsKey("phone") || changes.containsKey("email")) {
            Map<String, Object> current = db.queryForMap("SELECT candidate_name candidateName,phone,email FROM hr_employee_profile WHERE id=?", profileId);
            String finalName = name == null ? (String) current.get("candidateName") : name;
            String finalPhone = changes.containsKey("phone") ? phone : (String) current.get("phone");
            String finalEmail = changes.containsKey("email") ? email : (String) current.get("email");
            validateEmail(finalEmail);
            db.update("UPDATE hr_employee_profile SET candidate_name=?,phone=?,email=?,updated_at=NOW() WHERE id=?", finalName, finalPhone, finalEmail, profileId);
            db.update("UPDATE sys_user SET display_name=?,phone=?,email=?,version=version+1,updated_by=?,updated_at=NOW() WHERE id=? AND deleted_at IS NULL", finalName, finalPhone, finalEmail, operatorId, userId);
        }
    }

    private Map<String, Object> normalizeApprovalFields(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input == null) return result;
        for (String field : APPROVAL_FIELDS) {
            if (!input.containsKey(field)) continue;
            Object value = input.get(field);
            if ("educations".equals(field)) {
                List<Map<String, Object>> educations = normalizeEducations(value);
                if (!educations.isEmpty()) result.put(field, educations);
            } else if (hasContent(value)) {
                result.put(field, value instanceof String string ? string.trim() : value);
            }
        }
        if (result.containsKey("emergencyContactPhone") && text(result, "emergencyContactPhone") != null && !text(result, "emergencyContactPhone").matches("^[0-9+()\\- ]{5,32}$")) throw bad("紧急联系人电话格式不正确");
        return result;
    }

    private boolean hasContent(Object value) {
        return value != null && (!(value instanceof String string) || !string.isBlank());
    }

    private List<Map<String, Object>> normalizeEducations(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            Map<String, Object> education = objectMapper.convertValue(item, new TypeReference<>() {});
            if (education != null && education.values().stream().anyMatch(this::hasContent)) result.add(education);
        }
        return result;
    }

    private Map<String, Object> normalizeDirectFields(Map<String, Object> input) {
        Map<String, Object> result = normalizeApprovalFields(input);
        if (input != null) {
            for (String field : List.of("displayName", "phone", "email")) if (input.containsKey(field)) result.put(field, input.get(field));
        }
        if (result.containsKey("displayName") && text(result, "displayName") == null) throw bad("姓名不能为空");
        if (result.containsKey("email")) validateEmail(text(result, "email"));
        return result;
    }

    private long findApprover(long companyId, long applicantId) {
        List<Long> admins = db.query("SELECT uc.user_id FROM sys_user_company uc JOIN sys_user u ON u.id=uc.user_id WHERE uc.company_id=? AND uc.is_company_admin=1 AND uc.status='ENABLED' AND u.status='ENABLED' AND u.id<>? ORDER BY uc.user_id LIMIT 1", (rs, n) -> rs.getLong(1), companyId, applicantId);
        if (!admins.isEmpty()) return admins.get(0);
        List<Long> reviewers = db.query("SELECT DISTINCT uc.user_id FROM sys_user_company uc JOIN sys_user reviewer ON reviewer.id=uc.user_id AND reviewer.status='ENABLED' AND reviewer.deleted_at IS NULL WHERE uc.company_id=? AND uc.user_id<>? AND uc.status='ENABLED' AND uc.deleted_at IS NULL AND (uc.is_company_admin=1 OR EXISTS(SELECT 1 FROM sys_user_position up JOIN sys_position_permission pp ON pp.position_id=up.position_id AND pp.company_id=up.company_id JOIN sys_permission p ON p.id=pp.permission_id WHERE up.company_id=uc.company_id AND up.user_id=uc.user_id AND up.status='ENABLED' AND up.deleted_at IS NULL AND p.permission_code='hr:profile:approve') OR EXISTS(SELECT 1 FROM sys_user_permission uperm JOIN sys_permission p ON p.id=uperm.permission_id WHERE uperm.company_id=uc.company_id AND uperm.user_id=uc.user_id AND p.permission_code='hr:profile:approve' AND p.status='ENABLED' AND p.deleted_at IS NULL)) ORDER BY uc.user_id LIMIT 1", (rs, n) -> rs.getLong(1), companyId, applicantId);
        if (reviewers.isEmpty()) throw bad("未配置人员档案审批人，请联系公司管理员");
        return reviewers.get(0);
    }

    private void requireAdmin(ManagementUser user) { if (user.companyId() == null || !user.roles().contains("COMPANY_ADMIN")) throw forbidden(); }
    private void requireDirectEdit(ManagementUser user) { requireAdmin(user); }
    private void requireApprove(ManagementUser user) { if (user.companyId() == null || (!(user.roles().contains("COMPANY_ADMIN")) && !user.permissions().contains("hr:profile:approve"))) throw forbidden(); }
    private ManagementException forbidden() { return new ManagementException(HttpStatus.FORBIDDEN, "没有人员档案操作权限"); }
    private ManagementException notFound(String message) { return new ManagementException(HttpStatus.NOT_FOUND, message); }
    private ManagementException bad(String message) { return new ManagementException(HttpStatus.BAD_REQUEST, message); }
    private String profileSql() { return "SELECT ep.id,ep.user_id userId,ep.candidate_name candidateName,ep.gender,ep.birth_date birthDate,ep.id_type idType,ep.id_number_ciphertext idNumberCiphertext,ep.phone,ep.email,ep.address_ciphertext address,ep.emergency_contact_name emergencyContactName,ep.emergency_contact_relation emergencyContactRelation,ep.emergency_contact_phone emergencyContactPhone,ep.status,ep.submitted_at submittedAt,u.username,o.org_name departmentName,p.position_name positionName FROM hr_employee_profile ep JOIN sys_user u ON u.id=ep.user_id JOIN hr_onboarding_invitation i ON i.id=ep.invitation_id JOIN sys_org_unit o ON o.id=i.department_id JOIN sys_position p ON p.id=i.position_id"; }
    private Map<String, Object> withEducations(Map<String, Object> row) { return withEducations(row, false); }
    private Map<String, Object> withEducations(Map<String, Object> row, boolean revealIdNumber) { Map<String, Object> result = new LinkedHashMap<>(row); String idNumber = profileDataCrypto.decrypt((String) result.get("idNumberCiphertext")); result.put("idNumber", revealIdNumber ? idNumber : profileDataCrypto.mask(idNumber)); result.remove("idNumberCiphertext"); result.put("educations", db.queryForList("SELECT start_month startMonth,end_month endMonth,education_level educationLevel,school_name schoolName FROM hr_employee_education WHERE profile_id=? ORDER BY start_month DESC,end_month DESC,id DESC", row.get("id"))); return result; }
    private Map<String, Object> ownProfile(ManagementUser user, long profileId) {
        List<Map<String, Object>> rows = db.queryForList(profileSql() + " WHERE ep.id=? AND ep.user_id=? AND ep.company_id=?", profileId, user.id(), user.companyId());
        if (rows.isEmpty()) throw notFound("个人档案不存在");
        return withEducations(rows.get(0), false);
    }
    private long profileId(ManagementUser user) { return ((Number) myProfileRow(user).get("id")).longValue(); }
    private Map<String, Object> myProfileRow(ManagementUser user) {
        List<Map<String, Object>> rows = db.queryForList(profileSql() + " WHERE ep.user_id=? AND ep.company_id=?", user.id(), user.companyId());
        if (rows.isEmpty()) throw notFound("个人档案不存在");
        return rows.get(0);
    }
    private long insert(String sql, Object... args) { GeneratedKeyHolder holder = new GeneratedKeyHolder(); db.update(connection -> { var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); return ps; }, holder); return holder.getKey().longValue(); }
    private void log(ManagementUser actor, String type, long targetId, Object before, Object after) { try { db.update("INSERT INTO sys_operation_log(company_id,user_id,username,module_code,operation_type,target_type,target_id,before_data,after_data) VALUES(?,?,?,?,?,?,?,?,?)", actor.companyId(), actor.id(), actor.username(), "OA", type, "HR_PROFILE", String.valueOf(targetId), json(before), json(after)); } catch (Exception ignored) { } }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("档案数据序列化失败", ex); } }
    private Map<String, Object> readJson(String value) { try { return objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ex) { throw new IllegalStateException("档案申请数据损坏", ex); } }
    private String text(Map<String,Object> data, String key) { Object value = data.get(key); return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private LocalDate date(Map<String,Object> data, String key) { String value = text(data,key); return value == null ? null : LocalDate.parse(value); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void validateEmail(String value) { if (value != null && !value.isBlank() && !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw bad("邮箱格式不正确"); }
    private String hashNullable(String value) { if (value == null) return null; try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
