package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.auth.UserPhone;
import com.xinhe.nexus.management.auth.PasswordPolicy;
import com.xinhe.nexus.management.service.ManagementDtos.EducationInput;
import com.xinhe.nexus.management.service.ManagementDtos.OnboardingInvitationCreate;
import com.xinhe.nexus.management.service.ManagementDtos.OnboardingInvitationRegenerate;
import com.xinhe.nexus.management.service.ManagementDtos.OnboardingSubmit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeOnboardingService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> EDUCATION_LEVELS = List.of("HIGH_SCHOOL", "SECONDARY", "COLLEGE", "BACHELOR", "MASTER", "DOCTOR", "OTHER");
    private final JdbcTemplate db;
    private final PasswordEncoder passwordEncoder;
    private final ProfileDataCrypto profileDataCrypto;
    private final SecretKeySpec tokenKey;

    public EmployeeOnboardingService(JdbcTemplate db, PasswordEncoder passwordEncoder, ProfileDataCrypto profileDataCrypto, @Value("${xinhe.onboarding.token-encryption-key:change-this-onboarding-token-key-in-production}") String tokenEncryptionKey) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.profileDataCrypto = profileDataCrypto;
        this.tokenKey = new SecretKeySpec(sha256Bytes(tokenEncryptionKey), "AES");
    }

    @Transactional
    public Map<String, Object> createInvitation(ManagementUser actor, OnboardingInvitationCreate input) {
        requireAdmin(actor);
        String name = required(input.candidateName(), "姓名");
        if (input.departmentId() == null || input.positionId() == null) throw bad("部门和岗位不能为空");
        long companyId = actor.companyId();
        Integer valid = db.queryForObject("SELECT COUNT(*) FROM sys_position WHERE id=? AND company_id=? AND org_unit_id=? AND status='ENABLED' AND deleted_at IS NULL", Integer.class, input.positionId(), companyId, input.departmentId());
        if (valid == null || valid == 0) throw bad("部门与岗位不匹配或岗位不可用");
        int days = normalizeExpiryDays(input.expiresInDays());
        String token = randomToken();
        long id = insert("INSERT INTO hr_onboarding_invitation(company_id,candidate_name,department_id,position_id,token_hash,token_ciphertext,status,expires_at,created_by) VALUES(?,?,?,?,?,?, 'PENDING',?,?)", companyId, name, input.departmentId(), input.positionId(), hash(token), encryptToken(token), now().plusDays(days), actor.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("token", token); result.put("status", "PENDING"); result.put("expiresInDays", days);
        return result;
    }

    public List<Map<String, Object>> listInvitations(ManagementUser actor, String status) {
        requireAdmin(actor);
        expirePendingInvitations(actor.companyId());
        return db.queryForList("""
                SELECT i.id, i.candidate_name AS candidateName, i.department_id AS departmentId,
                       o.org_name AS departmentName, i.position_id AS positionId, p.position_name AS positionName,
                       i.status, i.expires_at AS expiresAt, i.submitted_at AS submittedAt, i.created_at AS createdAt,
                       ep.id AS profileId, CASE WHEN ep.id IS NULL THEN 'NOT_CREATED' ELSE 'CREATED' END AS profileStatus
                FROM hr_onboarding_invitation i
                JOIN sys_org_unit o ON o.id=i.department_id
                JOIN sys_position p ON p.id=i.position_id
                LEFT JOIN hr_employee_profile ep ON ep.invitation_id=i.id
                LEFT JOIN sys_user_company uc ON uc.user_id=ep.user_id AND uc.company_id=ep.company_id
                WHERE i.company_id=? AND (? IS NULL OR i.status=?)
                  AND uc.deleted_at IS NULL AND ep.deleted_at IS NULL
                ORDER BY i.created_at DESC
                """, actor.companyId(), status, status);
    }

    @Transactional
    public void revoke(ManagementUser actor, long id) {
        requireAdmin(actor);
        int changed = db.update("UPDATE hr_onboarding_invitation SET status='REVOKED',revoked_at=NOW(),updated_at=NOW() WHERE id=? AND company_id=? AND status='PENDING'", id, actor.companyId());
        if (changed != 1) throw conflict("只有待填写表单可以撤销");
    }

    @Transactional
    public Map<String, Object> regenerate(ManagementUser actor, long id, OnboardingInvitationRegenerate input) {
        requireAdmin(actor);
        String token = randomToken();
        int days = normalizeExpiryDays(input == null ? null : input.expiresInDays());
        int changed = db.update("UPDATE hr_onboarding_invitation SET token_hash=?,token_ciphertext=?,status='PENDING',expires_at=?,revoked_at=NULL,updated_at=? WHERE id=? AND company_id=? AND status IN ('PENDING','EXPIRED','REVOKED')", hash(token), encryptToken(token), now().plusDays(days), now(), id, actor.companyId());
        if (changed != 1) throw conflict("已提交表单不能重新生成链接");
        return Map.of("id", id, "token", token, "status", "PENDING", "expiresInDays", days);
    }

    public Map<String, Object> currentLink(ManagementUser actor, long id) {
        requireAdmin(actor);
        List<Map<String, Object>> rows = db.queryForList("SELECT token_ciphertext,status,expires_at FROM hr_onboarding_invitation WHERE id=? AND company_id=?", id, actor.companyId());
        if (rows.isEmpty()) throw notFound("入职表单不存在");
        Map<String, Object> row = rows.get(0);
        if (!"PENDING".equals(row.get("status"))) throw conflict("只有待填写表单可以复制链接");
        if (!asLocalDateTime(row.get("expires_at")).isAfter(now())) {
            db.update("UPDATE hr_onboarding_invitation SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING'", now(), id);
            throw conflict("表单已过期，请重新生成链接");
        }
        String ciphertext = (String) row.get("token_ciphertext");
        if (ciphertext == null || ciphertext.isBlank()) throw conflict("当前链接无法恢复，请重新生成链接");
        return Map.of("id", id, "token", decryptToken(ciphertext), "status", "PENDING");
    }

    public Map<String, Object> publicForm(String token) {
        if (token == null || token.isBlank()) throw notFound("表单不存在或已失效");
        List<Map<String, Object>> rows = db.queryForList("SELECT i.id,i.candidate_name AS candidateName,i.status,i.expires_at AS expiresAt,c.company_name AS companyName,o.org_name AS departmentName,p.position_name AS positionName FROM hr_onboarding_invitation i JOIN sys_company c ON c.id=i.company_id JOIN sys_org_unit o ON o.id=i.department_id JOIN sys_position p ON p.id=i.position_id WHERE i.token_hash=? AND NOT EXISTS (SELECT 1 FROM hr_employee_profile ep WHERE ep.invitation_id=i.id AND ep.deleted_at IS NOT NULL)", hash(token));
        if (rows.isEmpty()) throw notFound("表单不存在或已失效");
        Map<String, Object> row = rows.get(0);
        if ("PENDING".equals(row.get("status")) && !asLocalDateTime(row.get("expiresAt")).isAfter(now())) {
            db.update("UPDATE hr_onboarding_invitation SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING'", now(), row.get("id"));
            throw conflict("表单已过期");
        }
        if ("EXPIRED".equals(row.get("status"))) throw conflict("表单已过期，请联系管理员重新生成链接");
        if ("REVOKED".equals(row.get("status"))) throw conflict("表单已撤销，请联系管理员重新生成链接");
        if (!"PENDING".equals(row.get("status")) && !"SUBMITTED".equals(row.get("status"))) {
            throw conflict("表单状态无效，请联系管理员重新生成链接");
        }
        return Map.of("candidateName", row.get("candidateName"), "companyName", row.get("companyName"), "departmentName", row.get("departmentName"), "positionName", row.get("positionName"), "expiresAt", row.get("expiresAt"), "status", row.get("status"));
    }

    @Transactional
    public Map<String, Object> submit(String token, OnboardingSubmit input) {
        Map<String, Object> row = findPending(token);
        validateSubmit(input);
        long invitationId = ((Number) row.get("id")).longValue();
        String username = required(input.username(), "用户名");
        if (!username.matches("^[A-Za-z][A-Za-z0-9_.-]{2,63}$")) throw bad("用户名须以字母开头，长度为3-64位");
        if (count("SELECT COUNT(*) FROM sys_user WHERE username=? AND deleted_at IS NULL", username) > 0) throw conflict("用户名已存在，请更换");
        String phone = UserPhone.validate(db, input.phone(), null);
        long companyId = ((Number) row.get("companyId")).longValue();
        String name = (String) row.get("candidateName");
        long userId = insert("INSERT INTO sys_user(username,password_hash,display_name,user_type,status,must_change_password,phone,email) VALUES(?,?,?,'COMPANY_USER','ENABLED',0,?,?)", username, passwordEncoder.encode(input.password()), name, phone, trim(input.email()));
        db.update("INSERT INTO sys_user_company(user_id,company_id,is_company_admin,status) VALUES(?,?,0,'ENABLED')", userId, companyId);
        long positionId = ((Number) row.get("positionId")).longValue();
        db.update("INSERT INTO sys_user_position(company_id,user_id,position_id,status) VALUES(?,?,?,'ENABLED')", companyId, userId, positionId);
        long profileId = insert("INSERT INTO hr_employee_profile(invitation_id,user_id,company_id,candidate_name,gender,birth_date,id_type,id_number_ciphertext,id_number_hash,phone,email,address_ciphertext,emergency_contact_name,emergency_contact_relation,emergency_contact_phone,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'LOCKED')", invitationId, userId, companyId, name, trim(input.gender()), parseDate(input.birthDate()), trim(input.idType()), profileDataCrypto.encrypt(input.idNumber()), hashNullable(input.idNumber()), phone, trim(input.email()), trim(input.address()), trim(input.emergencyContactName()), trim(input.emergencyContactRelation()), normalizePhone(input.emergencyContactPhone()));
        List<EducationInput> educations = normalizedEducations(input.educations());
        int order = 0;
        for (EducationInput education : educations) {
            db.update("INSERT INTO hr_employee_education(profile_id,start_month,end_month,education_level,school_name,sort_order) VALUES(?,?,?,?,?,?)", profileId, education.startMonth(), education.endMonth(), education.educationLevel(), education.schoolName(), order++);
        }
        int changed = db.update("UPDATE hr_onboarding_invitation SET status='SUBMITTED',submitted_at=NOW(),updated_at=NOW() WHERE id=? AND status='PENDING'", invitationId);
        if (changed != 1) throw conflict("表单已提交或已失效");
        return Map.of("profileId", profileId, "userId", userId, "username", username, "status", "SUBMITTED");
    }

    public Map<String, Object> detail(ManagementUser actor, long id) {
        requireAdmin(actor);
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT ep.id,ep.candidate_name AS candidateName,ep.gender,ep.birth_date AS birthDate,
                       ep.id_type AS idType,ep.id_number_ciphertext AS idNumberCiphertext,ep.phone,ep.email,
                       ep.address_ciphertext AS address,ep.emergency_contact_name AS emergencyContactName,
                       ep.emergency_contact_relation AS emergencyContactRelation,
                       ep.emergency_contact_phone AS emergencyContactPhone,ep.status,ep.submitted_at AS submittedAt,
                       u.username,o.org_name AS departmentName,p.position_name AS positionName
                FROM hr_employee_profile ep
                LEFT JOIN sys_user u ON u.id=ep.user_id
                JOIN hr_onboarding_invitation i ON i.id=ep.invitation_id
                JOIN sys_org_unit o ON o.id=i.department_id
                JOIN sys_position p ON p.id=i.position_id
                LEFT JOIN sys_user_company uc ON uc.user_id=ep.user_id AND uc.company_id=ep.company_id
                WHERE ep.company_id=? AND ep.id=? AND uc.deleted_at IS NULL AND ep.deleted_at IS NULL
                """, actor.companyId(), id);
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "人员档案不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("idNumber", profileDataCrypto.mask(profileDataCrypto.decrypt((String) result.get("idNumberCiphertext"))));
        result.put("educations", db.queryForList("SELECT start_month AS startMonth,end_month AS endMonth,education_level AS educationLevel,school_name AS schoolName FROM hr_employee_education WHERE profile_id=? ORDER BY start_month DESC,end_month DESC,id DESC", id));
        result.remove("idNumberHash");
        result.remove("idNumberCiphertext");
        return result;
    }

    private Map<String, Object> findPending(String token) {
        if (token == null || token.isBlank()) throw notFound("表单不存在或已失效");
        List<Map<String, Object>> rows = db.queryForList("SELECT i.id,i.company_id AS companyId,i.candidate_name AS candidateName,i.position_id AS positionId,i.status,i.expires_at AS expiresAt,o.org_name AS departmentName,p.position_name AS positionName FROM hr_onboarding_invitation i JOIN sys_org_unit o ON o.id=i.department_id JOIN sys_position p ON p.id=i.position_id WHERE i.token_hash=?", hash(token));
        if (rows.isEmpty()) throw notFound("表单不存在或已失效");
        Map<String, Object> row = rows.get(0);
        if (!"PENDING".equals(row.get("status"))) throw conflict("该表单已提交或已失效");
        if (!asLocalDateTime(row.get("expiresAt")).isAfter(now())) {
            db.update("UPDATE hr_onboarding_invitation SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING'", now(), row.get("id"));
            throw conflict("表单已过期");
        }
        return row;
    }

    private void validateSubmit(OnboardingSubmit input) {
        required(input.gender(), "性别");
        required(input.birthDate(), "出生日期");
        required(input.idType(), "证件类型");
        required(input.idNumber(), "证件号码");
        required(input.phone(), "手机号");
        required(input.email(), "邮箱");
        required(input.address(), "居住地址");
        required(input.emergencyContactName(), "紧急联系人");
        required(input.emergencyContactRelation(), "紧急联系人关系");
        required(input.emergencyContactPhone(), "紧急联系人电话");
        required(input.password(), "密码");
        validateIdNumber(input.idType(), input.idNumber());
        validateMobile(input.phone(), "手机号");
        validateMobile(input.emergencyContactPhone(), "紧急联系人电话");
        if (!input.password().equals(input.confirmPassword())) throw bad("两次输入的密码不一致");
        try { PasswordPolicy.validate(input.password()); } catch (IllegalArgumentException ex) { throw bad(ex.getMessage()); }
        if (input.email() != null && !input.email().isBlank() && !input.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw bad("邮箱格式不正确");
        normalizedEducations(input.educations());
    }

    private List<EducationInput> normalizedEducations(List<EducationInput> input) {
        if (input == null || input.isEmpty()) return List.of();
        List<EducationInput> result = new ArrayList<>();
        for (EducationInput item : input) {
            if (item == null || item.startMonth() == null || item.endMonth() == null || item.educationLevel() == null || item.schoolName() == null || item.schoolName().isBlank()) throw bad("教育背景填写不完整");
            try { YearMonth start = YearMonth.parse(item.startMonth()); YearMonth end = YearMonth.parse(item.endMonth()); if (start.isAfter(end)) throw bad("教育背景开始时间不能晚于结束时间"); } catch (java.time.format.DateTimeParseException ex) { throw bad("教育背景时间必须为YYYY-MM格式"); }
            if (!EDUCATION_LEVELS.contains(item.educationLevel())) throw bad("教育程度无效");
            if (item.schoolName().trim().length() > 128) throw bad("院校名称不能超过128个字符");
            result.add(new EducationInput(item.startMonth(), item.endMonth(), item.educationLevel(), item.schoolName().trim()));
        }
        result.sort(Comparator.comparing(EducationInput::startMonth).reversed().thenComparing(EducationInput::endMonth, Comparator.reverseOrder()));
        return result;
    }

    private void requireAdmin(ManagementUser actor) { if (actor.companyId() == null || !(actor.roles().contains("COMPANY_ADMIN") || actor.roles().contains("PLATFORM_ADMIN") || actor.permissions().contains("*") || actor.permissions().contains("hr:employee:onboarding:create"))) throw new ManagementException(HttpStatus.FORBIDDEN, "没有人员档案管理权限"); }
    private long insert(String sql, Object... args) { GeneratedKeyHolder holder = new GeneratedKeyHolder(); db.update(connection -> { var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); return ps; }, holder); return holder.getKey().longValue(); }
    private int count(String sql, Object... args) { Integer count = db.queryForObject(sql, Integer.class, args); return count == null ? 0 : count; }
    private String required(String value, String label) { if (value == null || value.isBlank()) throw bad(label+"不能为空"); return value.trim(); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizePhone(String value) { String v=trim(value); if(v!=null&&!v.matches("^[0-9+()\\- ]{5,32}$")) throw bad("联系电话格式不正确"); return v; }
    private void validateMobile(String value, String label) { if (!required(value, label).matches("^1[3-9]\\d{9}$")) throw bad(label + "格式不正确，请填写11位大陆手机号"); }
    private void validateIdNumber(String idType, String idNumber) {
        String value = required(idNumber, "证件号码").toUpperCase(java.util.Locale.ROOT);
        if ("ID_CARD".equals(idType)) {
            if (!value.matches("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9X]$")) throw bad("身份证号码格式不正确");
            int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
            char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
            int sum = 0; for (int i = 0; i < 17; i++) sum += (value.charAt(i) - '0') * weights[i];
            if (checks[sum % 11] != value.charAt(17)) throw bad("身份证号码校验位不正确");
        } else if (value.length() < 3 || value.length() > 32) {
            throw bad("证件号码长度必须为3-32位");
        }
    }
    private LocalDate parseDate(String value) { if(value==null||value.isBlank()) return null; try{return LocalDate.parse(value);}catch(Exception e){throw bad("出生日期格式不正确");} }
    private LocalDateTime asLocalDateTime(Object value) { if (value instanceof LocalDateTime dateTime) return dateTime; if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime(); throw new IllegalStateException("无法读取表单有效期"); }
    private LocalDateTime now() { return LocalDateTime.now(BUSINESS_ZONE).withNano(0); }
    private int normalizeExpiryDays(Integer days) { return days == null ? 7 : Math.max(1, Math.min(days, 30)); }
    @Scheduled(fixedDelayString = "${xinhe.onboarding.expiry-cleanup-ms:300000}")
    public void expirePendingInvitations() { expirePendingInvitations(null); }
    private void expirePendingInvitations(Long companyId) {
        if (companyId == null) db.update("UPDATE hr_onboarding_invitation SET status='EXPIRED',updated_at=? WHERE status='PENDING' AND expires_at <= ?", now(), now());
        else db.update("UPDATE hr_onboarding_invitation SET status='EXPIRED',updated_at=? WHERE company_id=? AND status='PENDING' AND expires_at <= ?", now(), companyId, now());
    }
    private String hashNullable(String value) { return trim(value) == null ? null : hash(value); }
    private String hash(String value) { try { return HexFormatHolder.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private byte[] sha256Bytes(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private String encryptToken(String token) { try { byte[] iv = new byte[12]; RANDOM.nextBytes(iv); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, tokenKey, new GCMParameterSpec(128, iv)); byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)); byte[] result = new byte[iv.length + encrypted.length]; System.arraycopy(iv, 0, result, 0, iv.length); System.arraycopy(encrypted, 0, result, iv.length, encrypted.length); return Base64.getEncoder().encodeToString(result); } catch (Exception e) { throw new IllegalStateException("入职链接加密失败", e); } }
    private String decryptToken(String ciphertext) { try { byte[] value = Base64.getDecoder().decode(ciphertext); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, tokenKey, new GCMParameterSpec(128, Arrays.copyOfRange(value, 0, 12))); return new String(cipher.doFinal(Arrays.copyOfRange(value, 12, value.length)), StandardCharsets.UTF_8); } catch (Exception e) { throw new IllegalStateException("入职链接解密失败", e); } }
    private String randomToken() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private ManagementException bad(String message) { return new ManagementException(HttpStatus.BAD_REQUEST,message); }
    private ManagementException conflict(String message) { return new ManagementException(HttpStatus.CONFLICT,message); }
    private ManagementException notFound(String message) { return new ManagementException(HttpStatus.NOT_FOUND,message); }
    private static final class HexFormatHolder { static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b));return s.toString();} }
}
