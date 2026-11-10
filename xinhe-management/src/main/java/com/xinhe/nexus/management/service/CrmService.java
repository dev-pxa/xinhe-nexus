package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.UUID;

/**
 * CRM 首期业务服务。所有读取都以当前用户公司作为强制租户边界；
 * 行级可见范围来自岗位权限，而不是固定的“销售”角色。
 */
@Service
public class CrmService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final Path uploadRoot;
    public CrmService(JdbcTemplate db,@Value("${xinhe.crm.upload-dir:/data/uploads/crm}") String uploadDir) { this.db = db;this.uploadRoot=Path.of(uploadDir).toAbsolutePath().normalize(); }

    public List<Map<String,Object>> customers(ManagementUser u, String keyword, String phone, Long ownerUserId, Long categoryId, Boolean favorite) {
        access(u, "crm:customer:view");
        String scope = customerScope(u);
        StringBuilder sql = new StringBuilder("""
            WITH RECURSIVE category_paths (id,parent_id,category_path) AS (
              SELECT id,parent_id,CAST(category_name AS CHAR(2000)) FROM crm_customer_category WHERE company_id=? AND parent_id IS NULL AND deleted_at IS NULL
              UNION ALL
              SELECT child.id,child.parent_id,CONCAT(parent.category_path,' / ',child.category_name)
              FROM crm_customer_category child JOIN category_paths parent ON child.parent_id=parent.id
              WHERE child.company_id=? AND child.deleted_at IS NULL
            )
            SELECT c.id,c.customer_no customerNo,c.customer_name customerName,c.phone,
                   c.owner_user_id ownerUserId,ou.display_name ownerName,
                   CASE WHEN ou.status <> 'ENABLED' OR ou.deleted_at IS NOT NULL
                              OR ouc.status <> 'ENABLED' OR ouc.deleted_at IS NOT NULL
                        THEN 'DEPARTED' ELSE 'ACTIVE' END ownerStatus,
                   c.category_id categoryId,cat.category_path categoryPath,
                   CASE WHEN fav.id IS NULL THEN FALSE ELSE TRUE END isFavorite,
                   0 AS contractCount
            FROM crm_customer c LEFT JOIN sys_user ou ON ou.id=c.owner_user_id
            LEFT JOIN sys_user_company ouc ON ouc.user_id=c.owner_user_id AND ouc.company_id=c.company_id
            LEFT JOIN category_paths cat ON cat.id=c.category_id
            LEFT JOIN crm_customer_favorite fav ON fav.company_id=c.company_id AND fav.customer_id=c.id AND fav.user_id=? AND fav.deleted_at IS NULL
            WHERE c.company_id=? AND c.deleted_at IS NULL AND %s
            """.formatted(scope));
        List<Object> args = new java.util.ArrayList<>(List.of(u.companyId(),u.companyId(),u.id(),u.companyId()));
        String search=blankToNull(keyword);
        if(search!=null){sql.append(" AND c.customer_name LIKE CONCAT('%',?,'%')");args.add(search);}
        String phoneSearch=blankToNull(phone);
        if(phoneSearch!=null){sql.append(" AND c.phone LIKE CONCAT('%',?,'%')");args.add(phoneSearch);}
        if(ownerUserId!=null){sql.append(" AND c.owner_user_id=?");args.add(ownerUserId);}
        if(categoryId!=null){sql.append(" AND c.category_id=?");args.add(categoryId);}
        if(Boolean.TRUE.equals(favorite))sql.append(" AND fav.id IS NOT NULL");
        if(Boolean.FALSE.equals(favorite))sql.append(" AND fav.id IS NULL");
        sql.append(" ORDER BY c.updated_at DESC");
        return db.queryForList(sql.toString(),args.toArray());
    }

    public Map<String,Object> customer(ManagementUser u, long id) {
        access(u,"crm:customer:view"); ensureCustomerVisible(u,id);
        Map<String,Object> result=db.queryForMap("SELECT c.id,c.customer_no customerNo,c.customer_name customerName,c.phone,c.address,c.source,c.category_id categoryId,cat.category_name categoryName,c.owner_user_id ownerUserId,ou.display_name ownerName,CASE WHEN ou.status <> 'ENABLED' OR ou.deleted_at IS NOT NULL OR ouc.status <> 'ENABLED' OR ouc.deleted_at IS NOT NULL THEN 'DEPARTED' ELSE 'ACTIVE' END ownerStatus,c.remark,c.version FROM crm_customer c LEFT JOIN sys_user ou ON ou.id=c.owner_user_id LEFT JOIN sys_user_company ouc ON ouc.user_id=c.owner_user_id AND ouc.company_id=c.company_id LEFT JOIN crm_customer_category cat ON cat.id=c.category_id AND cat.company_id=c.company_id AND cat.deleted_at IS NULL WHERE c.company_id=? AND c.id=? AND c.deleted_at IS NULL",u.companyId(),id);
        result.put("collaboratorUserIds",db.queryForList("SELECT user_id FROM crm_customer_collaborator WHERE company_id=? AND customer_id=? AND deleted_at IS NULL",Long.class,u.companyId(),id));
        return result;
    }

    /** 仅返回已具备客户查看权限的本公司员工，用作负责人/协作人下拉选择数据源。 */
    public List<Map<String,Object>> eligibleUsers(ManagementUser u){access(u,"crm:customer:view");return db.queryForList("SELECT DISTINCT us.id,us.display_name name FROM sys_user us JOIN sys_user_company uc ON uc.user_id=us.id LEFT JOIN sys_user_position up ON up.user_id=us.id AND up.company_id=uc.company_id AND up.status='ENABLED' AND up.deleted_at IS NULL LEFT JOIN sys_position_permission pp ON pp.position_id=up.position_id LEFT JOIN sys_permission pm ON pm.id=pp.permission_id WHERE uc.company_id=? AND uc.status='ENABLED' AND us.status='ENABLED' AND us.deleted_at IS NULL AND (pm.permission_code='crm:customer:view' OR us.id=?) ORDER BY us.display_name",u.companyId(),u.id());}
    public List<Map<String,Object>> categories(ManagementUser u){access(u,"crm:customer:view");return db.queryForList("SELECT id,parent_id parentId,category_name categoryName,sort_order sortOrder FROM crm_customer_category WHERE company_id=? AND deleted_at IS NULL ORDER BY sort_order,category_name,id",u.companyId());}
    @Transactional public long createCategory(ManagementUser u,CategoryInput input){access(u,"crm:customer:create");String name=required(input.categoryName(),"分类名称");if(input.parentId()!=null&&db.queryForList("SELECT id FROM crm_customer_category WHERE company_id=? AND id=? AND deleted_at IS NULL",u.companyId(),input.parentId()).isEmpty())throw new ManagementException(HttpStatus.BAD_REQUEST,"上级分类不存在");return insert("INSERT INTO crm_customer_category(company_id,parent_id,category_name,created_by,updated_by) VALUES(?,?,?,?,?)",u.companyId(),input.parentId(),name,u.id(),u.id());}
    @Transactional public void setFavorite(ManagementUser u,long customerId,boolean favorite){access(u,"crm:customer:view");ensureCustomerVisible(u,customerId);if(favorite)db.update("INSERT INTO crm_customer_favorite(company_id,customer_id,user_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE deleted_at=NULL,updated_at=NOW()",u.companyId(),customerId,u.id());else db.update("UPDATE crm_customer_favorite SET deleted_at=NOW(),updated_at=NOW() WHERE company_id=? AND customer_id=? AND user_id=? AND deleted_at IS NULL",u.companyId(),customerId,u.id());}
    public List<Map<String,Object>> myCustomers(ManagementUser u){access(u,"crm:customer:view");return db.queryForList("SELECT id,customer_no customerNo,customer_name customerName FROM crm_customer WHERE company_id=? AND owner_user_id=? AND deleted_at IS NULL ORDER BY customer_name",u.companyId(),u.id());}

    @Transactional public Map<String,Object> saveCustomer(ManagementUser u, Long id, CustomerInput in) {
        access(u,id==null?"crm:customer:create":"crm:customer:edit");
        String name = required(in.customerName(),"客户名称");
        String phone=required(in.phone(),"联系电话");
        String address=required(in.address(),"详细地址");
        String source=required(in.source(),"来源渠道");
        validateSource(source);
        if(in.categoryId()==null)throw new ManagementException(HttpStatus.BAD_REQUEST,"客户分类不能为空");
        if(id==null) {
            validateCategory(u,in.categoryId());
            long customerId = insert("INSERT INTO crm_customer(company_id,customer_no,customer_name,phone,address,source,category_id,owner_user_id,remark,created_by,updated_by) VALUES(?,'PENDING',?,?,?,?,?,?,?,?,?)",
                u.companyId(),name,phone,address,source,in.categoryId(),u.id(),trim(in.remark()),u.id(),u.id());
            String number="KH"+String.format("%08d",customerId);
            db.update("UPDATE crm_customer SET customer_no=? WHERE id=? AND company_id=?",number,customerId,u.companyId());
            if(in.collaboratorUserIds()!=null)updateCollaborators(u,customerId,in.collaboratorUserIds());
            return Map.of("id",customerId,"customerNo",number);
        }
        ensureCustomerVisible(u,id);
        validateCategory(u,in.categoryId());
        Map<String,Object> current=customer(u,id);
        long owner=((Number)current.get("ownerUserId")).longValue();
        if(in.ownerUserId()!=null && in.ownerUserId()!=owner) {
            access(u,"crm:customer:owner:edit"); validateAssignable(u,in.ownerUserId()); owner=in.ownerUserId();
        }
        if(in.collaboratorUserIds()!=null)updateCollaborators(u,id,in.collaboratorUserIds());
        int count=db.update("UPDATE crm_customer SET customer_name=?,phone=?,address=?,source=?,category_id=?,remark=?,owner_user_id=?,version=version+1,updated_by=? WHERE company_id=? AND id=? AND version=? AND deleted_at IS NULL",
            name,phone,address,source,in.categoryId(),trim(in.remark()),owner,u.id(),u.companyId(),id,in.version()==null?((Number)current.get("version")).intValue():in.version());
        if(count!=1) throw new ManagementException(HttpStatus.CONFLICT,"客户信息已被其他人更新，请刷新后重试");
        return customer(u,id);
    }

    public List<Map<String,Object>> followups(ManagementUser u,long customerId) {
        access(u,"crm:followup:view"); ensureCustomerVisible(u,customerId);
        List<Map<String,Object>> rows=db.queryForList("SELECT f.id,f.occurred_at occurredAt,f.method,f.content,f.customer_feedback customerFeedback,f.quote_summary quoteSummary,f.quote_amount quoteAmount,f.commitment,f.next_action nextAction,f.next_action_at nextActionAt,f.created_by createdBy,usr.display_name creatorName FROM crm_follow_up f JOIN sys_user usr ON usr.id=f.created_by WHERE f.company_id=? AND f.customer_id=? ORDER BY f.occurred_at DESC,f.id DESC",u.companyId(),customerId);
        for(Map<String,Object> row:rows)row.put("attachments",db.queryForList("SELECT id,file_name fileName,content_type contentType,file_size fileSize,uploaded_by uploadedBy,created_at createdAt FROM crm_follow_up_attachment WHERE company_id=? AND follow_up_id=? AND deleted_at IS NULL ORDER BY id",u.companyId(),row.get("id")));
        return rows;
    }
    @Transactional public long addFollowup(ManagementUser u,long customerId,FollowupInput f) {
        access(u,"crm:followup:create"); ensureCustomerVisible(u,customerId);
        if(f.content()==null||f.content().isBlank())throw new ManagementException(HttpStatus.BAD_REQUEST,"跟进内容不能为空");
        String method=f.method()==null?"VISIT":f.method();
        if(!List.of("VISIT","ONLINE","OTHER").contains(method))throw new ManagementException(HttpStatus.BAD_REQUEST,"跟进方式仅支持拜访、线上或其他");
        return insert("INSERT INTO crm_follow_up(company_id,customer_id,occurred_at,method,content,customer_feedback,quote_summary,quote_amount,commitment,next_action,next_action_at,created_by) VALUES(?,?,COALESCE(?,NOW()),?,?,?,?,?,?,?,?,?)",
            u.companyId(),customerId,normalizeDateTime(f.occurredAt()),method,f.content().trim(),trim(f.customerFeedback()),trim(f.quoteSummary()),f.quoteAmount(),trim(f.commitment()),trim(f.nextAction()),normalizeDateTime(f.nextActionAt()),u.id());
    }
    /** 附件先校验父跟踪记录可见性，再保存到公司隔离目录并写元数据，不信任客户端提供的对象路径。 */
    @Transactional public long uploadFollowupAttachment(ManagementUser u,long followupId,MultipartFile file){
        access(u,"crm:attachment:upload");if(file==null||file.isEmpty())throw new ManagementException(HttpStatus.BAD_REQUEST,"附件不能为空");
        if(file.getSize()>20L*1024*1024)throw new ManagementException(HttpStatus.BAD_REQUEST,"单个附件不能超过20MB");
        String type=file.getContentType()==null?"":file.getContentType().toLowerCase(java.util.Locale.ROOT);
        if(!List.of("application/pdf","image/jpeg","image/png").contains(type))throw new ManagementException(HttpStatus.BAD_REQUEST,"附件仅支持PDF、JPG和PNG");
        Map<String,Object> f=db.queryForMap("SELECT customer_id customerId FROM crm_follow_up WHERE company_id=? AND id=?",u.companyId(),followupId);ensureCustomerVisible(u,((Number)f.get("customerId")).longValue());
        String name=file.getOriginalFilename()==null?"attachment":Path.of(file.getOriginalFilename()).getFileName().toString();String ext=name.contains(".")?name.substring(name.lastIndexOf('.')):"";
        String key="company/"+u.companyId()+"/"+LocalDate.now(BUSINESS_ZONE)+"/"+UUID.randomUUID()+ext;Path target=uploadRoot.resolve(key).normalize();if(!target.startsWith(uploadRoot))throw new ManagementException(HttpStatus.BAD_REQUEST,"附件路径无效");
        try{Files.createDirectories(target.getParent());Files.copy(file.getInputStream(),target,StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new ManagementException(HttpStatus.INTERNAL_SERVER_ERROR,"附件保存失败");}
        return insert("INSERT INTO crm_follow_up_attachment(company_id,follow_up_id,file_name,object_key,content_type,file_size,uploaded_by) VALUES(?,?,?,?,?,?,?)",u.companyId(),followupId,name,key,type,file.getSize(),u.id());
    }
    public Resource followupAttachment(ManagementUser u,long attachmentId){access(u,"crm:attachment:download");Map<String,Object> row=db.queryForMap("SELECT a.object_key objectKey,f.customer_id customerId FROM crm_follow_up_attachment a JOIN crm_follow_up f ON f.id=a.follow_up_id AND f.company_id=a.company_id WHERE a.company_id=? AND a.id=? AND a.deleted_at IS NULL",u.companyId(),attachmentId);ensureCustomerVisible(u,((Number)row.get("customerId")).longValue());Path path=uploadRoot.resolve((String)row.get("objectKey")).normalize();if(!path.startsWith(uploadRoot)||!Files.exists(path))throw new ManagementException(HttpStatus.NOT_FOUND,"附件不存在");return new FileSystemResource(path);}
    /** 获取响应 MIME 类型时同样校验附件所属客户，避免用附件 ID 探测无权客户的元数据。 */
    public String followupAttachmentContentType(ManagementUser u,long id){
        access(u,"crm:attachment:download");
        Map<String,Object> row=db.queryForMap("SELECT a.content_type contentType,f.customer_id customerId FROM crm_follow_up_attachment a JOIN crm_follow_up f ON f.id=a.follow_up_id AND f.company_id=a.company_id WHERE a.company_id=? AND a.id=? AND a.deleted_at IS NULL",u.companyId(),id);
        ensureCustomerVisible(u,((Number)row.get("customerId")).longValue());
        return (String)row.get("contentType");
    }

    public Map<String,Object> plans(ManagementUser u,int year,int month,int page,int pageSize,Long ownerUserId) {
        access(u,"crm:plan:view"); String scope=planScope(u); page=Math.max(page,1); pageSize=Math.min(Math.max(pageSize,1),100);
        LocalDate from=LocalDate.of(year,month,1),to=from.plusMonths(1); String ownerFilter=ownerUserId==null?"":" AND p.owner_user_id=?";
        List<Object> args=new java.util.ArrayList<>(List.of(u.companyId(),from,to)); if(ownerUserId!=null)args.add(ownerUserId); args.add(pageSize); args.add((page-1)*pageSize);
        List<Map<String,Object>> rows=db.queryForList("""
          SELECT p.id,DATE_FORMAT(p.plan_date,'%%Y-%%m-%%d') planDate,p.owner_user_id ownerUserId,u.display_name ownerName,
            CASE WHEN u.status <> 'ENABLED' OR u.deleted_at IS NOT NULL OR uc.status <> 'ENABLED' OR uc.deleted_at IS NOT NULL THEN 'DEPARTED' ELSE 'ACTIVE' END ownerStatus,
            COUNT(i.id) planCount,SUM(CASE WHEN i.id IS NOT NULL AND i.is_completed=0 AND p.plan_date<CURDATE() THEN 1 ELSE 0 END) delayedCount,
            CASE WHEN COUNT(i.id)=0 THEN 0 ELSE ROUND(SUM(i.is_completed=1)*100/COUNT(i.id),1) END completionRate,
            COUNT(DISTINCT i.customer_id) associatedCustomerCount,p.version
          FROM crm_daily_plan p LEFT JOIN sys_user u ON u.id=p.owner_user_id
          LEFT JOIN sys_user_company uc ON uc.user_id=p.owner_user_id AND uc.company_id=p.company_id
          LEFT JOIN crm_daily_plan_item i ON i.daily_plan_id=p.id AND i.deleted_at IS NULL
          WHERE p.company_id=? AND p.plan_date>=? AND p.plan_date<? AND p.deleted_at IS NULL AND %s%s
          GROUP BY p.id,p.plan_date,p.owner_user_id,u.display_name,u.status,u.deleted_at,uc.status,uc.deleted_at,p.version ORDER BY p.plan_date DESC,u.display_name LIMIT ? OFFSET ?
          """.formatted(scope,ownerFilter),args.toArray());
        long total=countPlans(u,scope,"p.plan_date>=? AND p.plan_date<?",ownerUserId,from,to);
        return Map.of("data",rows,"total",total,"page",page,"pageSize",pageSize);
    }
    public Map<String,Object> plan(ManagementUser u,long id) {
        access(u,"crm:plan:view"); ensurePlanVisible(u,id);
        // 显式序列化为 ISO 日期字符串，避免 JDBC DATE 被 Jackson 转成 UTC 时间戳后跨日显示。
        Map<String,Object> head=db.queryForMap("SELECT p.id,DATE_FORMAT(p.plan_date,'%Y-%m-%d') planDate,p.owner_user_id ownerUserId,usr.display_name ownerName,CASE WHEN usr.status <> 'ENABLED' OR usr.deleted_at IS NOT NULL OR uc.status <> 'ENABLED' OR uc.deleted_at IS NOT NULL THEN 'DEPARTED' ELSE 'ACTIVE' END ownerStatus,p.version FROM crm_daily_plan p LEFT JOIN sys_user usr ON usr.id=p.owner_user_id LEFT JOIN sys_user_company uc ON uc.user_id=p.owner_user_id AND uc.company_id=p.company_id WHERE p.company_id=? AND p.id=? AND p.deleted_at IS NULL",u.companyId(),id);
        List<Map<String,Object>> items=db.queryForList("SELECT id,content_html contentHtml,content_text contentText,customer_id customerId,remark,is_completed isCompleted,version,sort_order sortOrder FROM crm_daily_plan_item WHERE company_id=? AND daily_plan_id=? AND deleted_at IS NULL ORDER BY sort_order,id",u.companyId(),id);
        head.put("items",items);return head;
    }
    public List<Map<String,Object>> dailyPlanDraftItems(ManagementUser u) {
        // 先定位目标日期之前最近的一条每日计划，再只带出该计划的未完成项；中间空档或请假不会改变来源计划。
        access(u,"crm:plan:create"); LocalDate today=LocalDate.now(BUSINESS_ZONE);
        return db.queryForList("SELECT i.content_html contentHtml,CASE WHEN c.id IS NOT NULL AND c.owner_user_id=? THEN i.customer_id ELSE NULL END customerId,i.remark FROM crm_daily_plan p JOIN crm_daily_plan_item i ON i.daily_plan_id=p.id AND i.company_id=p.company_id AND i.deleted_at IS NULL LEFT JOIN crm_customer c ON c.company_id=i.company_id AND c.id=i.customer_id AND c.deleted_at IS NULL WHERE p.company_id=? AND p.owner_user_id=? AND p.plan_date=(SELECT previous.plan_date FROM crm_daily_plan previous WHERE previous.company_id=? AND previous.owner_user_id=? AND previous.plan_date<? AND previous.deleted_at IS NULL ORDER BY previous.plan_date DESC,previous.id DESC LIMIT 1) AND p.deleted_at IS NULL AND i.is_completed=0 ORDER BY i.sort_order,i.id",u.id(),u.companyId(),u.id(),u.companyId(),u.id(),today);
    }
    @Transactional public long createPlan(ManagementUser u,PlanInput in) {
        access(u,"crm:plan:create"); LocalDate today=LocalDate.now(BUSINESS_ZONE);
        Long existing=db.query("SELECT id FROM crm_daily_plan WHERE company_id=? AND owner_user_id=? AND plan_date=? AND deleted_at IS NULL",rs->rs.next()?rs.getLong(1):null,u.companyId(),u.id(),today);
        // 并发或重复点击不得将已有日计划项目静默覆盖；客户端应打开既有记录进入编辑。
        if(existing!=null)throw new ManagementException(HttpStatus.CONFLICT,"今天已存在每日计划，请在列表中编辑");
        // 用岗位关系主键作稳定回退顺序，兼容已运行环境中尚未带 is_primary 字段的旧表结构。
        long id=insert("INSERT INTO crm_daily_plan(company_id,owner_user_id,owner_org_id,plan_date,created_by,updated_by) VALUES(?,?,(SELECT p.org_unit_id FROM sys_user_position up JOIN sys_position p ON p.id=up.position_id WHERE up.company_id=? AND up.user_id=? AND up.status='ENABLED' AND up.deleted_at IS NULL ORDER BY up.id LIMIT 1),?,?,?)",u.companyId(),u.id(),u.companyId(),u.id(),today,u.id(),u.id());
        replaceItems(u,id,in.items()); return id;
    }
    @Transactional public void updatePlan(ManagementUser u,long id,PlanInput in) { access(u,"crm:plan:edit"); ensurePlanEditable(u,id); replaceItems(u,id,in.items()); }
    @Transactional public void deletePlan(ManagementUser u,long id) { access(u,"crm:plan:delete"); ensurePlanEditable(u,id); db.update("DELETE FROM crm_daily_plan_item WHERE company_id=? AND daily_plan_id=?",u.companyId(),id); db.update("DELETE FROM crm_daily_plan WHERE company_id=? AND id=?",u.companyId(),id); }
    @Transactional public void completeItem(ManagementUser u,long itemId,boolean complete) { access(u,"crm:plan:complete"); Long planId=db.queryForObject("SELECT daily_plan_id FROM crm_daily_plan_item WHERE company_id=? AND id=? AND deleted_at IS NULL",Long.class,u.companyId(),itemId); ensurePlanEditable(u,planId); db.update("UPDATE crm_daily_plan_item SET is_completed=?,completed_at=?,completed_by=?,version=version+1 WHERE company_id=? AND id=?",complete?1:0,complete?java.sql.Timestamp.from(java.time.Instant.now()):null,complete?u.id():null,u.companyId(),itemId); }

    /** 每周计划以周一作为周期锚点，其他权限范围与每日计划保持一致。 */
    public Map<String,Object> weeklyPlans(ManagementUser u,int year,int month,int page,int pageSize,Long ownerUserId) {
        access(u,"crm:plan:view"); page=Math.max(page,1); pageSize=Math.min(Math.max(pageSize,1),100); LocalDate from=LocalDate.of(year,month,1),to=from.plusMonths(1); String scope=planScope(u); String ownerFilter=ownerUserId==null?"":" AND p.owner_user_id=?";
        List<Object> args=new java.util.ArrayList<>(List.of(u.companyId(),to,from)); if(ownerUserId!=null)args.add(ownerUserId); args.add(pageSize); args.add((page-1)*pageSize);
        List<Map<String,Object>> rows=db.queryForList("""
          SELECT p.id,DATE_FORMAT(p.week_start,'%%Y-%%m-%%d') weekStart,DATE_FORMAT(DATE_ADD(p.week_start,INTERVAL 6 DAY),'%%Y-%%m-%%d') weekEnd,
            p.owner_user_id ownerUserId,u.display_name ownerName,
            CASE WHEN u.status <> 'ENABLED' OR u.deleted_at IS NOT NULL OR uc.status <> 'ENABLED' OR uc.deleted_at IS NOT NULL THEN 'DEPARTED' ELSE 'ACTIVE' END ownerStatus,
            COUNT(i.id) planCount,SUM(CASE WHEN i.id IS NOT NULL AND i.is_completed=0 AND DATE_ADD(p.week_start,INTERVAL 6 DAY)<CURDATE() THEN 1 ELSE 0 END) delayedCount,
            CASE WHEN COUNT(i.id)=0 THEN 0 ELSE ROUND(SUM(i.is_completed=1)*100/COUNT(i.id),1) END completionRate,
            COUNT(DISTINCT i.customer_id) associatedCustomerCount,p.version
          FROM crm_weekly_plan p LEFT JOIN sys_user u ON u.id=p.owner_user_id
          LEFT JOIN sys_user_company uc ON uc.user_id=p.owner_user_id AND uc.company_id=p.company_id
          LEFT JOIN crm_weekly_plan_item i ON i.weekly_plan_id=p.id AND i.deleted_at IS NULL
          WHERE p.company_id=? AND p.week_start<? AND DATE_ADD(p.week_start,INTERVAL 6 DAY)>=? AND p.deleted_at IS NULL AND %s%s
          GROUP BY p.id,p.week_start,p.owner_user_id,u.display_name,u.status,u.deleted_at,uc.status,uc.deleted_at,p.version ORDER BY p.week_start DESC,u.display_name LIMIT ? OFFSET ?
          """.formatted(scope,ownerFilter),args.toArray());
        long total=countWeeklyPlans(u,scope,ownerUserId,to,from);
        return Map.of("data",rows,"total",total,"page",page,"pageSize",pageSize);
    }
    public Map<String,Object> weeklyPlan(ManagementUser u,long id) {
        access(u,"crm:plan:view"); ensureWeeklyPlanVisible(u,id);
        Map<String,Object> head=db.queryForMap("SELECT p.id,DATE_FORMAT(p.week_start,'%Y-%m-%d') weekStart,DATE_FORMAT(DATE_ADD(p.week_start,INTERVAL 6 DAY),'%Y-%m-%d') weekEnd,p.owner_user_id ownerUserId,usr.display_name ownerName,CASE WHEN usr.status <> 'ENABLED' OR usr.deleted_at IS NOT NULL OR uc.status <> 'ENABLED' OR uc.deleted_at IS NOT NULL THEN 'DEPARTED' ELSE 'ACTIVE' END ownerStatus,p.version FROM crm_weekly_plan p LEFT JOIN sys_user usr ON usr.id=p.owner_user_id LEFT JOIN sys_user_company uc ON uc.user_id=p.owner_user_id AND uc.company_id=p.company_id WHERE p.company_id=? AND p.id=? AND p.deleted_at IS NULL",u.companyId(),id);
        head.put("items",db.queryForList("SELECT id,content_html contentHtml,content_text contentText,customer_id customerId,remark,is_completed isCompleted,version,sort_order sortOrder FROM crm_weekly_plan_item WHERE company_id=? AND weekly_plan_id=? AND deleted_at IS NULL ORDER BY sort_order,id",u.companyId(),id)); return head;
    }
    public List<Map<String,Object>> weeklyPlanDraftItems(ManagementUser u) {
        // 先定位目标周期之前最近的一条每周计划，再只带出该计划的未完成项；不按上一周强制匹配。
        access(u,"crm:plan:create"); LocalDate monday=LocalDate.now(BUSINESS_ZONE).with(java.time.DayOfWeek.MONDAY);
        return db.queryForList("SELECT i.content_html contentHtml,CASE WHEN c.id IS NOT NULL AND c.owner_user_id=? THEN i.customer_id ELSE NULL END customerId,i.remark FROM crm_weekly_plan p JOIN crm_weekly_plan_item i ON i.weekly_plan_id=p.id AND i.company_id=p.company_id AND i.deleted_at IS NULL LEFT JOIN crm_customer c ON c.company_id=i.company_id AND c.id=i.customer_id AND c.deleted_at IS NULL WHERE p.company_id=? AND p.owner_user_id=? AND p.week_start=(SELECT previous.week_start FROM crm_weekly_plan previous WHERE previous.company_id=? AND previous.owner_user_id=? AND previous.week_start<? AND previous.deleted_at IS NULL ORDER BY previous.week_start DESC,previous.id DESC LIMIT 1) AND p.deleted_at IS NULL AND i.is_completed=0 ORDER BY i.sort_order,i.id",u.id(),u.companyId(),u.id(),u.companyId(),u.id(),monday);
    }
    @Transactional public long createWeeklyPlan(ManagementUser u,PlanInput in) {
        access(u,"crm:plan:create"); LocalDate today=LocalDate.now(BUSINESS_ZONE); LocalDate monday=today.with(java.time.DayOfWeek.MONDAY);
        Long existing=db.query("SELECT id FROM crm_weekly_plan WHERE company_id=? AND owner_user_id=? AND week_start=? AND deleted_at IS NULL",rs->rs.next()?rs.getLong(1):null,u.companyId(),u.id(),monday);
        if(existing!=null)throw new ManagementException(HttpStatus.CONFLICT,"本周已存在每周计划，请在列表中编辑");
        long id=insert("INSERT INTO crm_weekly_plan(company_id,owner_user_id,owner_org_id,week_start,created_by,updated_by) VALUES(?,?,(SELECT p.org_unit_id FROM sys_user_position up JOIN sys_position p ON p.id=up.position_id WHERE up.company_id=? AND up.user_id=? AND up.status='ENABLED' AND up.deleted_at IS NULL ORDER BY up.id LIMIT 1),?,?,?)",u.companyId(),u.id(),u.companyId(),u.id(),monday,u.id(),u.id());
        replaceWeeklyItems(u,id,in.items()); return id;
    }
    @Transactional public void updateWeeklyPlan(ManagementUser u,long id,PlanInput in) { access(u,"crm:plan:edit"); ensureWeeklyPlanEditable(u,id); replaceWeeklyItems(u,id,in.items()); }
    @Transactional public void deleteWeeklyPlan(ManagementUser u,long id) { access(u,"crm:plan:delete"); ensureWeeklyPlanEditable(u,id); db.update("DELETE FROM crm_weekly_plan_item WHERE company_id=? AND weekly_plan_id=?",u.companyId(),id); db.update("DELETE FROM crm_weekly_plan WHERE company_id=? AND id=?",u.companyId(),id); }
    @Transactional public void completeWeeklyItem(ManagementUser u,long itemId,boolean complete) { access(u,"crm:plan:complete"); Long planId=db.queryForObject("SELECT weekly_plan_id FROM crm_weekly_plan_item WHERE company_id=? AND id=? AND deleted_at IS NULL",Long.class,u.companyId(),itemId); ensureWeeklyPlanEditable(u,planId); db.update("UPDATE crm_weekly_plan_item SET is_completed=?,completed_at=?,completed_by=?,version=version+1 WHERE company_id=? AND id=?",complete?1:0,complete?java.sql.Timestamp.from(java.time.Instant.now()):null,complete?u.id():null,u.companyId(),itemId); }
    private void replaceWeeklyItems(ManagementUser u,long planId,List<PlanItem> items) {
        if(items==null)items=List.of(); List<Long> retained=items.stream().filter(item->item.contentHtml()!=null&&!item.contentHtml().isBlank()).map(PlanItem::id).filter(java.util.Objects::nonNull).distinct().toList();
        if(retained.isEmpty())db.update("UPDATE crm_weekly_plan_item SET deleted_at=NOW() WHERE company_id=? AND weekly_plan_id=? AND deleted_at IS NULL",u.companyId(),planId);
        else db.update("UPDATE crm_weekly_plan_item SET deleted_at=NOW() WHERE company_id=? AND weekly_plan_id=? AND deleted_at IS NULL AND id NOT IN ("+String.join(",",retained.stream().map(x->"?").toList())+")",concat(new Object[]{u.companyId(),planId},retained.toArray()));
        int order=0; for(PlanItem item:items){if(item.contentHtml()==null||item.contentHtml().isBlank())continue; Long customer=item.customerId(); if(customer!=null){ensureCustomerVisible(u,customer);Long owner=db.queryForObject("SELECT owner_user_id FROM crm_customer WHERE company_id=? AND id=? AND deleted_at IS NULL",Long.class,u.companyId(),customer);if(owner!=u.id())throw new ManagementException(HttpStatus.FORBIDDEN,"计划只能关联自己负责的客户");}String safe=sanitize(item.contentHtml());String text=safe.replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").trim();
            if(item.id()!=null){int changed=db.update("UPDATE crm_weekly_plan_item SET content_html=?,content_text=?,customer_id=?,remark=?,sort_order=?,version=version+1 WHERE company_id=? AND weekly_plan_id=? AND id=? AND deleted_at IS NULL",safe,text,customer,trim(item.remark()),order++,u.companyId(),planId,item.id());if(changed!=1)throw new ManagementException(HttpStatus.BAD_REQUEST,"计划项无效或已被移除");}
            else db.update("INSERT INTO crm_weekly_plan_item(company_id,weekly_plan_id,content_html,content_text,customer_id,remark,sort_order,created_by) VALUES(?,?,?,?,?,?,?,?)",u.companyId(),planId,safe,text,customer,trim(item.remark()),order++,u.id());
        } db.update("UPDATE crm_weekly_plan SET updated_by=?,version=version+1 WHERE company_id=? AND id=?",u.id(),u.companyId(),planId);
    }
    private long countPlans(ManagementUser u,String scope,String dateCondition,Long ownerUserId,LocalDate from,LocalDate to){
        String ownerFilter=ownerUserId==null?"":" AND p.owner_user_id=?";
        List<Object> args=new java.util.ArrayList<>(List.of(u.companyId(),from,to)); if(ownerUserId!=null)args.add(ownerUserId);
        Long total=db.queryForObject(("SELECT COUNT(*) FROM crm_daily_plan p WHERE p.company_id=? AND "+dateCondition+" AND p.deleted_at IS NULL AND "+scope+ownerFilter),Long.class,args.toArray());
        return total==null?0:total;
    }
    private long countWeeklyPlans(ManagementUser u,String scope,Long ownerUserId,LocalDate to,LocalDate from){
        String ownerFilter=ownerUserId==null?"":" AND p.owner_user_id=?";
        List<Object> args=new java.util.ArrayList<>(List.of(u.companyId(),to,from)); if(ownerUserId!=null)args.add(ownerUserId);
        Long total=db.queryForObject(("SELECT COUNT(*) FROM crm_weekly_plan p WHERE p.company_id=? AND p.week_start<? AND DATE_ADD(p.week_start,INTERVAL 6 DAY)>=? AND p.deleted_at IS NULL AND "+scope+ownerFilter),Long.class,args.toArray());
        return total==null?0:total;
    }
    private void ensureWeeklyPlanVisible(ManagementUser u,long id){if(db.queryForList("SELECT p.id FROM crm_weekly_plan p WHERE p.company_id=? AND p.id=? AND p.deleted_at IS NULL AND "+planScope(u),u.companyId(),id).isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"每周计划不存在或无权查看");}
    private void ensureWeeklyPlanEditable(ManagementUser u,long id){ensureWeeklyPlanVisible(u,id);Map<String,Object> plan=db.queryForMap("SELECT owner_user_id,week_start FROM crm_weekly_plan WHERE company_id=? AND id=?",u.companyId(),id);if(((Number)plan.get("owner_user_id")).longValue()!=u.id())throw new ManagementException(HttpStatus.FORBIDDEN,"仅计划归属人可以编辑或删除计划");if(((java.sql.Date)plan.get("week_start")).toLocalDate().plusDays(6).isBefore(LocalDate.now(BUSINESS_ZONE)))throw new ManagementException(HttpStatus.CONFLICT,"已过期的每周计划只能查看，不能编辑");}

    private void replaceItems(ManagementUser u,long planId,List<PlanItem> items) {
        if(items==null)items=List.of();
        // 只软删除本次提交中被移除的项目，保留仍存在项目的 ID 与完成状态。
        List<Long> retained=items.stream().filter(item->item.contentHtml()!=null&&!item.contentHtml().isBlank()).map(PlanItem::id).filter(java.util.Objects::nonNull).distinct().toList();
        if(retained.isEmpty())db.update("UPDATE crm_daily_plan_item SET deleted_at=NOW() WHERE company_id=? AND daily_plan_id=? AND deleted_at IS NULL",u.companyId(),planId);
        else db.update("UPDATE crm_daily_plan_item SET deleted_at=NOW() WHERE company_id=? AND daily_plan_id=? AND deleted_at IS NULL AND id NOT IN ("+String.join(",",retained.stream().map(x->"?").toList())+")",concat(new Object[]{u.companyId(),planId},retained.toArray()));
        int order=0;
        for(PlanItem item:items){if(item.contentHtml()==null||item.contentHtml().isBlank())continue;
            Long customer=item.customerId(); if(customer!=null){ensureCustomerVisible(u,customer); Long owner=db.queryForObject("SELECT owner_user_id FROM crm_customer WHERE company_id=? AND id=? AND deleted_at IS NULL",Long.class,u.companyId(),customer); if(owner!=u.id())throw new ManagementException(HttpStatus.FORBIDDEN,"计划只能关联自己负责的客户");}
            String safe=sanitize(item.contentHtml());String text=safe.replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").trim();
            if(item.id()!=null){
                int changed=db.update("UPDATE crm_daily_plan_item SET content_html=?,content_text=?,customer_id=?,remark=?,sort_order=?,version=version+1 WHERE company_id=? AND daily_plan_id=? AND id=? AND deleted_at IS NULL",safe,text,customer,trim(item.remark()),order++,u.companyId(),planId,item.id());
                if(changed!=1)throw new ManagementException(HttpStatus.BAD_REQUEST,"计划项无效或已被移除");
            }else db.update("INSERT INTO crm_daily_plan_item(company_id,daily_plan_id,content_html,content_text,customer_id,remark,sort_order,created_by) VALUES(?,?,?,?,?,?,?,?)",u.companyId(),planId,safe,text,customer,trim(item.remark()),order++,u.id());
        }
        db.update("UPDATE crm_daily_plan SET updated_by=?,version=version+1 WHERE company_id=? AND id=?",u.id(),u.companyId(),planId);
    }
    /**
     * 富文本采用小型 allow-list 净化：移除可执行容器、所有标签属性和非允许标签，
     * 只保留编辑器实际支持的格式节点。属性全部移除意味着链接目标、事件处理器、CSS URL
     * 均不会被保存在 HTML 中；普通文本仍保留为可读内容。
     */
    private String sanitize(String html){
        String safe=html.replaceAll("(?is)<(script|style|iframe|object|svg|math)[^>]*>.*?</\\1\\s*>","");
        java.util.regex.Matcher tags=java.util.regex.Pattern.compile("(?is)<\\s*(/?)\\s*([a-z0-9]+)[^>]*>").matcher(safe);
        StringBuffer out=new StringBuffer();java.util.Set<String> allowed=java.util.Set.of("b","strong","i","em","u","p","br","ul","ol","li","blockquote","a");
        while(tags.find()){String tag=tags.group(2).toLowerCase(java.util.Locale.ROOT);String replacement=allowed.contains(tag)?"<"+tags.group(1)+tag+">":"";tags.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));}
        tags.appendTail(out);return out.toString();
    }
    private void ensureCustomerVisible(ManagementUser u,long id){if(db.queryForList("SELECT c.id FROM crm_customer c WHERE c.company_id=? AND c.id=? AND c.deleted_at IS NULL AND "+customerScope(u),u.companyId(),id).isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"客户不存在或无权查看");}
    private void ensurePlanVisible(ManagementUser u,long id){if(db.queryForList("SELECT p.id FROM crm_daily_plan p WHERE p.company_id=? AND p.id=? AND p.deleted_at IS NULL AND "+planScope(u),u.companyId(),id).isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"计划不存在或无权查看");}
    private void ensurePlanEditable(ManagementUser u,long id){ensurePlanVisible(u,id);Map<String,Object> plan=db.queryForMap("SELECT owner_user_id,plan_date FROM crm_daily_plan WHERE company_id=? AND id=?",u.companyId(),id);if(((Number)plan.get("owner_user_id")).longValue()!=u.id())throw new ManagementException(HttpStatus.FORBIDDEN,"仅计划归属人可以编辑或删除计划");if(((java.sql.Date)plan.get("plan_date")).toLocalDate().isBefore(LocalDate.now(BUSINESS_ZONE)))throw new ManagementException(HttpStatus.CONFLICT,"已过期的每日计划只能查看，不能编辑");}
    /**
     * 行级范围直接按当前人的负责组织计算：仅本人及其负责组织树内成员可见。
     * 普通岗位仅能查看本人/协作客户和本人计划；没有使用抽象的通用数据范围枚举。
     */
    private String customerScope(ManagementUser u){
        String own="(c.owner_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM crm_customer_collaborator cc WHERE cc.company_id=c.company_id AND cc.customer_id=c.id AND cc.user_id="+u.id()+" AND cc.deleted_at IS NULL))";
        if(!team(u,"crm:customer:team-view"))return own;
        // 负责人岗位只要仍保留历史关系即可用于定位客户原组织；删除用户会把岗位置为
        // DISABLED，但不删除该关系。查看者自己的负责人关系仍必须是 ENABLED，避免
        // 离任部门负责人继续凭旧岗位查看团队客户。
        return "(c.owner_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM sys_user_position op JOIN sys_position opos ON opos.id=op.position_id JOIN sys_org_unit emporg ON emporg.id=opos.org_unit_id JOIN sys_org_unit mgrorg ON mgrorg.company_id=emporg.company_id AND emporg.org_path LIKE CONCAT(mgrorg.org_path,'%') WHERE op.company_id=c.company_id AND op.user_id=c.owner_user_id AND op.deleted_at IS NULL AND (mgrorg.manager_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM sys_user_position mp WHERE mp.company_id=mgrorg.company_id AND mp.user_id="+u.id()+" AND mp.position_id=mgrorg.manager_position_id AND mp.status='ENABLED' AND mp.deleted_at IS NULL))))";
    }
    private String planScope(ManagementUser u){
        if(!team(u,"crm:plan:team-view"))return "p.owner_user_id="+u.id();
        // 日计划创建时已保存归属组织快照，不再依赖归属人的当前岗位关系；离职后仍能
        // 按原组织归属查看历史计划。查看者本人仍必须在组织负责人岗位上保持有效。
        return "(p.owner_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM sys_org_unit mgrorg WHERE mgrorg.company_id=p.company_id AND p.owner_org_id IS NOT NULL AND EXISTS(SELECT 1 FROM sys_org_unit emporg WHERE emporg.company_id=p.company_id AND emporg.id=p.owner_org_id AND emporg.org_path LIKE CONCAT(mgrorg.org_path,'%')) AND (mgrorg.manager_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM sys_user_position mp WHERE mp.company_id=mgrorg.company_id AND mp.user_id="+u.id()+" AND mp.position_id=mgrorg.manager_position_id AND mp.status='ENABLED' AND mp.deleted_at IS NULL))))";
    }
    private boolean team(ManagementUser u,String permission){return u.permissions().contains("*")||u.roles().contains("COMPANY_ADMIN")||u.permissions().contains(permission);}
    private void access(ManagementUser u,String p){if(!(u.permissions().contains("*")||u.roles().contains("COMPANY_ADMIN")||u.permissions().contains(p)))throw new ManagementException(HttpStatus.FORBIDDEN,"当前岗位未配置CRM操作权限："+p);if(u.companyId()==null)throw new ManagementException(HttpStatus.FORBIDDEN,"账号未关联公司");}
    private void validateCategory(ManagementUser u,Long categoryId){if(categoryId!=null&&db.queryForList("SELECT id FROM crm_customer_category WHERE company_id=? AND id=? AND deleted_at IS NULL",u.companyId(),categoryId).isEmpty())throw new ManagementException(HttpStatus.BAD_REQUEST,"客户分类不存在");}
    private void validateSource(String source){String value=trim(source);if(value!=null&&!List.of("抖音","小红书","老客户推荐","设计师推荐","材料商推荐","扫楼","其他").contains(value))throw new ManagementException(HttpStatus.BAD_REQUEST,"客户来源渠道无效");}
    private void validateAssignable(ManagementUser u,long user){if(db.queryForList("SELECT 1 FROM sys_user_company uc JOIN sys_user us ON us.id=uc.user_id WHERE uc.company_id=? AND uc.user_id=? AND uc.status='ENABLED' AND us.status='ENABLED' AND us.deleted_at IS NULL",u.companyId(),user).isEmpty())throw new ManagementException(HttpStatus.BAD_REQUEST,"负责人必须是本公司启用用户");if(db.queryForList("SELECT 1 FROM sys_user_position up JOIN sys_position_permission pp ON pp.position_id=up.position_id JOIN sys_permission p ON p.id=pp.permission_id WHERE up.company_id=? AND up.user_id=? AND up.status='ENABLED' AND p.permission_code='crm:customer:view'",u.companyId(),user).isEmpty())throw new ManagementException(HttpStatus.BAD_REQUEST,"负责人尚未获得CRM权限");}
    private void updateCollaborators(ManagementUser u,long customerId,List<Long> ids){access(u,"crm:customer:collaborator:edit");db.update("UPDATE crm_customer_collaborator SET deleted_at=NOW() WHERE company_id=? AND customer_id=? AND deleted_at IS NULL",u.companyId(),customerId);if(ids==null)return;for(Long id:ids){if(id==null||id==u.id())continue;validateAssignable(u,id);db.update("INSERT INTO crm_customer_collaborator(company_id,customer_id,user_id,created_by) VALUES(?,?,?,?)",u.companyId(),customerId,id,u.id());}}
    private String required(String s,String label){if(s==null||s.isBlank())throw new ManagementException(HttpStatus.BAD_REQUEST,label+"不能为空");return s.trim();}
    private String normalizeDateTime(String value){String clean=trim(value);if(clean==null)return null;return clean.replace('T',' ').replaceAll("Z$","");}
    /** JDBC 的 INSERT 使用 GeneratedKeyHolder 读取主键，避免依赖不兼容的 INSERT 返回值语法。 */
    private long insert(String sql,Object... args){var key=new GeneratedKeyHolder();db.update(c->{var ps=c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);if(key.getKey()==null)throw new IllegalStateException("数据库未返回新增记录ID");return key.getKey().longValue();}
    private Object[] concat(Object[] first,Object[] second){Object[] joined=java.util.Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,joined,first.length,second.length);return joined;}
    private String trim(String s){return s==null||s.isBlank()?null:s.trim();} private String blankToNull(String s){return trim(s);}
    public record CustomerInput(String customerName,String phone,String address,String source,Long categoryId,Long ownerUserId,List<Long> collaboratorUserIds,String remark,Integer version){}
    public record CategoryInput(String categoryName,Long parentId){}
    public record PlanInput(List<PlanItem> items){} public record PlanItem(Long id,String contentHtml,Long customerId,String remark){}
    public record FollowupInput(String occurredAt,String method,String content,String customerFeedback,String quoteSummary,java.math.BigDecimal quoteAmount,String commitment,String nextAction,String nextActionAt){}
}
