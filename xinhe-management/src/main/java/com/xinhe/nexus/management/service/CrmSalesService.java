package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRM销售订单申请、负责人审批，以及审批通过后同步ERP正式订单。 */
@Service
public class CrmSalesService {
    private final JdbcTemplate db;
    public CrmSalesService(JdbcTemplate db) { this.db = db; }

    /** CRM开单商品选择器，仅暴露开单所需的已启用商品，不要求销售人员拥有ERP模块权限。 */
    public List<Map<String,Object>> orderProducts(ManagementUser u) {
        requireAny(u,"crm:sales-order:create","crm:sales-order:edit");
        return db.queryForList("""
            SELECT p.id,p.product_code productCode,p.product_name productName,
                   c.category_name categoryName,p.model,p.specification,p.unit_price unitPrice,p.status
            FROM erp_product p
            JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id
            WHERE p.company_id=? AND p.status='ENABLED'
            ORDER BY p.product_code,p.id
            """,u.companyId());
    }

    /** 销售订单主列表：普通人员仅本人，组织负责人可看负责组织树内人员，公司管理员可看本公司。 */
    public List<Map<String,Object>> orders(ManagementUser u,String applicationNo,String orderNo,String customerName,String salespersonName,String approvalStatus) {
        require(u,"crm:sales-order:view");
        StringBuilder sql=new StringBuilder("""
            SELECT o.id,o.application_no applicationNo,o.customer_id customerId,o.customer_name_snapshot customerName,
                   o.salesperson_user_id salespersonUserId,s.display_name salespersonName,o.approval_status approvalStatus,
                   o.total_amount totalAmount,o.created_at createdAt,o.approved_at approvedAt,
                   e.id erpOrderId,e.order_no orderNo,e.fulfillment_status fulfillmentStatus
            FROM crm_sales_order o JOIN sys_user s ON s.id=o.salesperson_user_id
            LEFT JOIN erp_sales_fulfillment_order e ON e.id=o.erp_order_id AND e.company_id=o.company_id
            WHERE o.company_id=? AND ("""+salesOrderScope(u)+")");
        List<Object> args=new ArrayList<>();args.add(u.companyId());
        if(trim(applicationNo)!=null){sql.append(" AND o.application_no LIKE CONCAT('%',?,'%')");args.add(trim(applicationNo));}
        if(trim(orderNo)!=null){sql.append(" AND e.order_no LIKE CONCAT('%',?,'%')");args.add(trim(orderNo));}
        if(trim(customerName)!=null){sql.append(" AND o.customer_name_snapshot LIKE CONCAT('%',?,'%')");args.add(trim(customerName));}
        if(trim(salespersonName)!=null){sql.append(" AND s.display_name LIKE CONCAT('%',?,'%')");args.add(trim(salespersonName));}
        if(trim(approvalStatus)!=null){sql.append(" AND o.approval_status=?");args.add(trim(approvalStatus));}
        sql.append(" ORDER BY o.created_at DESC,o.id DESC");
        return db.queryForList(sql.toString(),args.toArray());
    }

    public List<Map<String,Object>> customerOrders(ManagementUser u,long customerId) {
        require(u,"crm:sales-order:view"); ensureCustomerVisible(u,customerId);
        return db.queryForList("""
            SELECT o.id,o.application_no applicationNo,o.customer_id customerId,o.customer_name_snapshot customerName,
                   o.approval_status approvalStatus,o.total_amount totalAmount,o.created_at createdAt,o.approved_at approvedAt,
                   e.id erpOrderId,e.order_no orderNo,e.fulfillment_status fulfillmentStatus,s.display_name salespersonName
            FROM crm_sales_order o JOIN sys_user s ON s.id=o.salesperson_user_id
            LEFT JOIN erp_sales_fulfillment_order e ON e.id=o.erp_order_id AND e.company_id=o.company_id
            WHERE o.company_id=? AND o.customer_id=? ORDER BY o.id DESC
            """,u.companyId(),customerId);
    }

    public List<Map<String,Object>> pendingApprovals(ManagementUser u) {
        require(u,"crm:sales-order:approve");
        return db.queryForList("SELECT o.id,o.application_no applicationNo,o.customer_name_snapshot customerName,o.total_amount totalAmount,o.created_at createdAt,s.display_name salespersonName,TRUE canApprove FROM crm_sales_order o JOIN sys_user s ON s.id=o.salesperson_user_id WHERE o.company_id=? AND o.approval_status='PENDING_APPROVAL' AND ("+salesOrderManagerScope(u)+") ORDER BY o.created_at",u.companyId());
    }

    public Map<String,Object> order(ManagementUser u,long id) {
        require(u,"crm:sales-order:view");
        ensureOrderVisible(u,id);
        List<Map<String,Object>> rows=db.queryForList("""
            SELECT o.id,o.application_no applicationNo,o.customer_id customerId,o.customer_name_snapshot customerName,
                   o.phone_snapshot phone,o.shipping_address_snapshot shippingAddress,o.salesperson_user_id salespersonUserId,
                   s.display_name salespersonName,o.approval_status approvalStatus,o.total_amount totalAmount,o.remark,
                   o.approver_user_id approverUserId,o.approval_comment approvalComment,o.approved_at approvedAt,
                   o.erp_order_id erpOrderId,e.order_no orderNo,e.fulfillment_status fulfillmentStatus,o.version,o.created_at createdAt
            FROM crm_sales_order o JOIN sys_user s ON s.id=o.salesperson_user_id
            LEFT JOIN erp_sales_fulfillment_order e ON e.id=o.erp_order_id AND e.company_id=o.company_id
            WHERE o.company_id=? AND o.id=?
            """,u.companyId(),id);
        if(rows.isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"销售订单不存在");
        Map<String,Object> result=rows.get(0);
        if(result.get("approvalComment") instanceof String approvalComment)result.put("approvalComment",sanitizeRichText(approvalComment));
        result.put("canApprove",canManageOrder(u,id));
        result.put("lines",db.queryForList("SELECT id,line_no lineNo,product_id productId,product_code_snapshot productCode,product_name_snapshot productName,category_name_snapshot categoryName,product_model_snapshot model,product_specification_snapshot specification,quantity,unit_price unitPrice,amount FROM crm_sales_order_line WHERE company_id=? AND order_id=? ORDER BY line_no",u.companyId(),id));
        List<Map<String,Object>> logs=db.queryForList("SELECT action,from_status fromStatus,to_status toStatus,opinion,operated_at operatedAt,u.display_name operatedByName FROM crm_sales_order_log l JOIN sys_user u ON u.id=l.operated_by WHERE l.company_id=? AND l.order_id=? ORDER BY l.id",u.companyId(),id);
        for(Map<String,Object> log:logs)if(log.get("opinion") instanceof String opinion)log.put("opinion",sanitizeRichText(opinion));
        result.put("logs",logs);
        return result;
    }

    @Transactional
    public Map<String,Object> create(ManagementUser u,OrderInput input) {
        require(u,"crm:sales-order:create");
        Map<String,Object> customer=ownedCustomer(u,input.customerId());
        long approver=departmentApprover(u);
        String applicationNo="CRM-SO-"+String.format("%08d",nextNumber(u.companyId(),"CRM_SALES_ORDER"));
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        db.update(c->{var ps=c.prepareStatement("INSERT INTO crm_sales_order(company_id,application_no,customer_id,customer_name_snapshot,phone_snapshot,shipping_address_snapshot,salesperson_user_id,approval_status,total_amount,remark,approver_user_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,'PENDING_APPROVAL',0,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,u.companyId());ps.setString(2,applicationNo);ps.setLong(3,input.customerId());ps.setString(4,(String)customer.get("customerName"));ps.setString(5,trim(input.phone()));ps.setString(6,trim(input.shippingAddress()));ps.setLong(7,u.id());ps.setString(8,trim(input.remark()));ps.setLong(9,approver);ps.setLong(10,u.id());ps.setLong(11,u.id());return ps;},key);
        long id=key.getKey().longValue();
        BigDecimal total=replaceLines(u,id,input.lines());
        db.update("UPDATE crm_sales_order SET total_amount=? WHERE company_id=? AND id=?",total,u.companyId(),id);
        log(u,id,"SUBMIT",null,"PENDING_APPROVAL",null);
        return Map.of("id",id,"applicationNo",applicationNo,"approvalStatus","PENDING_APPROVAL","totalAmount",total);
    }

    @Transactional
    public Map<String,Object> resubmit(ManagementUser u,long id,OrderInput input) {
        require(u,"crm:sales-order:edit");
        List<Map<String,Object>> rows=db.queryForList("SELECT approval_status approvalStatus,version FROM crm_sales_order WHERE company_id=? AND id=? AND created_by=? FOR UPDATE",u.companyId(),id,u.id());
        if(rows.isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"销售订单不存在或不可编辑");
        String from=(String)rows.get(0).get("approvalStatus");
        if(!Set.of("REJECTED","WITHDRAWN").contains(from))throw bad("只有已驳回或已撤回订单可以修改重提");
        Map<String,Object> customer=ownedCustomer(u,input.customerId());
        long approver=departmentApprover(u);
        db.update("DELETE FROM crm_sales_order_line WHERE company_id=? AND order_id=?",u.companyId(),id);
        BigDecimal total=replaceLines(u,id,input.lines());
        int changed=db.update("UPDATE crm_sales_order SET customer_id=?,customer_name_snapshot=?,phone_snapshot=?,shipping_address_snapshot=?,total_amount=?,remark=?,approver_user_id=?,approval_status='PENDING_APPROVAL',approval_comment=NULL,approved_at=NULL,version=version+1,updated_by=? WHERE company_id=? AND id=? AND version=?",input.customerId(),customer.get("customerName"),trim(input.phone()),trim(input.shippingAddress()),total,trim(input.remark()),approver,u.id(),u.companyId(),id,input.version()==null?((Number)rows.get(0).get("version")).intValue():input.version());
        if(changed!=1)throw new ManagementException(HttpStatus.CONFLICT,"订单已被其他人修改，请刷新后重试");
        log(u,id,"RESUBMIT",from,"PENDING_APPROVAL",null);
        return order(u,id);
    }

    @Transactional
    public void withdraw(ManagementUser u,long id) {
        require(u,"crm:sales-order:withdraw");
        int changed=db.update("UPDATE crm_sales_order SET approval_status='WITHDRAWN',version=version+1,updated_by=? WHERE company_id=? AND id=? AND created_by=? AND approval_status='PENDING_APPROVAL'",u.id(),u.companyId(),id,u.id());
        if(changed!=1)throw bad("只有本人待审批订单可以撤回");
        log(u,id,"WITHDRAW","PENDING_APPROVAL","WITHDRAWN",null);
    }

    @Transactional
    public Map<String,Object> decide(ManagementUser u,long id,boolean approve,String opinion) {
        require(u,"crm:sales-order:approve");
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM crm_sales_order WHERE company_id=? AND id=? FOR UPDATE",u.companyId(),id);
        if(rows.isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"销售订单不存在");
        Map<String,Object> order=rows.get(0);
        if(!"PENDING_APPROVAL".equals(order.get("approval_status")))throw new ManagementException(HttpStatus.BAD_REQUEST,"订单不在待审核状态");
        if(!canManageOrder(u,id))throw new ManagementException(HttpStatus.FORBIDDEN,"你不是该销售人员组织链上的负责人，无权审批此订单");
        String safeOpinion=sanitizeRichText(opinion);
        if(!approve){db.update("UPDATE crm_sales_order SET approval_status='REJECTED',approval_comment=?,version=version+1,updated_by=? WHERE company_id=? AND id=?",safeOpinion,u.id(),u.companyId(),id);log(u,id,"REJECT","PENDING_APPROVAL","REJECTED",safeOpinion);return order(u,id);}
        String orderNo="SO-"+String.format("%08d",nextNumber(u.companyId(),"SALES_ORDER"));
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        db.update(c->{var ps=c.prepareStatement("INSERT INTO erp_sales_fulfillment_order(company_id,crm_sales_order_id,order_no,customer_id,customer_name_snapshot,phone_snapshot,shipping_address_snapshot,salesperson_user_id,total_amount,remark,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,u.companyId());ps.setLong(2,id);ps.setString(3,orderNo);if(order.get("customer_id")==null)ps.setNull(4,java.sql.Types.BIGINT);else ps.setLong(4,((Number)order.get("customer_id")).longValue());ps.setString(5,(String)order.get("customer_name_snapshot"));ps.setString(6,(String)order.get("phone_snapshot"));ps.setString(7,(String)order.get("shipping_address_snapshot"));ps.setLong(8,((Number)order.get("salesperson_user_id")).longValue());ps.setBigDecimal(9,(BigDecimal)order.get("total_amount"));ps.setString(10,(String)order.get("remark"));ps.setLong(11,u.id());ps.setLong(12,u.id());return ps;},key);
        long erpId=key.getKey().longValue();
        db.update("INSERT INTO erp_sales_fulfillment_line(company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount) SELECT company_id,?,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount FROM crm_sales_order_line WHERE company_id=? AND order_id=?",erpId,u.companyId(),id);
        db.update("UPDATE crm_sales_order SET approval_status='APPROVED',approval_comment=?,approved_at=NOW(),erp_order_id=?,version=version+1,updated_by=? WHERE company_id=? AND id=?",safeOpinion,erpId,u.id(),u.companyId(),id);
        log(u,id,"APPROVE_SYNC_ERP","PENDING_APPROVAL","APPROVED",safeOpinion);
        return Map.of("id",id,"approvalStatus","APPROVED","erpOrderId",erpId,"orderNo",orderNo);
    }

    private BigDecimal replaceLines(ManagementUser u,long orderId,List<OrderLineInput> lines) {
        if(lines==null||lines.isEmpty())throw bad("请至少添加一条商品明细");
        Set<Long> products=new HashSet<>();BigDecimal total=BigDecimal.ZERO;int no=1;
        for(OrderLineInput line:lines){if(line.quantity()==null||line.quantity()<=0)throw bad("订购数量必须为正整数");if(!products.add(line.productId()))throw bad("同一商品不能重复添加");
            List<Map<String,Object>> found=db.queryForList("SELECT p.product_code,p.product_name,c.category_name,p.model,p.specification,p.unit_price FROM erp_product p JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id WHERE p.company_id=? AND p.id=? AND p.status='ENABLED'",u.companyId(),line.productId());
            if(found.isEmpty())throw bad("商品不存在或已停用");Map<String,Object> p=found.get(0);BigDecimal price=(BigDecimal)p.get("unit_price");BigDecimal amount=price.multiply(BigDecimal.valueOf(line.quantity())).setScale(2);total=total.add(amount);
            db.update("INSERT INTO crm_sales_order_line(company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",u.companyId(),orderId,no++,line.productId(),p.get("product_code"),p.get("product_name"),p.get("category_name"),p.get("model"),p.get("specification"),line.quantity(),price,amount);
        }return total;
    }
    private Map<String,Object> ownedCustomer(ManagementUser u,Long id){if(id==null)throw bad("请选择客户");List<Map<String,Object>> rows=db.queryForList("SELECT id,customer_name customerName,phone,address FROM crm_customer WHERE company_id=? AND id=? AND owner_user_id=? AND deleted_at IS NULL",u.companyId(),id,u.id());if(rows.isEmpty())throw bad("只能选择本人负责的客户");return rows.get(0);}
    private void ensureOrderVisible(ManagementUser u,long id){if(db.queryForList("SELECT o.id FROM crm_sales_order o WHERE o.company_id=? AND o.id=? AND "+salesOrderScope(u),u.companyId(),id).isEmpty())throw new ManagementException(HttpStatus.NOT_FOUND,"销售订单不存在或无权查看");}
    private String salesOrderScope(ManagementUser u){
        if(admin(u))return "1=1";
        return "(o.salesperson_user_id="+u.id()+" OR "+salesOrderManagerScope(u)+")";
    }
    /** 直属审批人及销售人员所在组织链上的所有负责人均可查看和审批。 */
    private String salesOrderManagerScope(ManagementUser u){
        if(admin(u))return "1=1";
        return "(o.approver_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM sys_user_position op JOIN sys_position opos ON opos.id=op.position_id JOIN sys_org_unit emporg ON emporg.id=opos.org_unit_id JOIN sys_org_unit mgrorg ON mgrorg.company_id=emporg.company_id AND emporg.org_path LIKE CONCAT(mgrorg.org_path,'%') WHERE op.company_id=o.company_id AND op.user_id=o.salesperson_user_id AND op.deleted_at IS NULL AND (mgrorg.manager_user_id="+u.id()+" OR EXISTS(SELECT 1 FROM sys_user_position mp WHERE mp.company_id=mgrorg.company_id AND mp.user_id="+u.id()+" AND mp.position_id=mgrorg.manager_position_id AND mp.status='ENABLED' AND mp.deleted_at IS NULL))))";
    }
    boolean canManageOrder(ManagementUser u,long id){
        if(admin(u))return true;
        return !db.queryForList("SELECT o.id FROM crm_sales_order o WHERE o.company_id=? AND o.id=? AND ("+salesOrderManagerScope(u)+")",u.companyId(),id).isEmpty();
    }
    private void ensureCustomerVisible(ManagementUser u,long id){if(admin(u))return;Integer n=db.queryForObject("SELECT COUNT(*) FROM crm_customer c WHERE c.company_id=? AND c.id=? AND c.deleted_at IS NULL AND (c.owner_user_id=? OR EXISTS(SELECT 1 FROM crm_customer_collaborator cc WHERE cc.company_id=c.company_id AND cc.customer_id=c.id AND cc.user_id=? AND cc.deleted_at IS NULL))",Integer.class,u.companyId(),id,u.id(),u.id());if(n==null||n==0)throw new ManagementException(HttpStatus.NOT_FOUND,"客户不存在或无权查看");}
    private long departmentApprover(ManagementUser u){List<Long> orgs=db.query("SELECT p.org_unit_id FROM sys_user_position up JOIN sys_position p ON p.id=up.position_id AND p.status='ENABLED' AND p.deleted_at IS NULL WHERE up.company_id=? AND up.user_id=? AND up.status='ENABLED' AND up.deleted_at IS NULL ORDER BY up.id",(rs,n)->rs.getLong(1),u.companyId(),u.id());for(Long orgId:orgs){String path=db.queryForObject("SELECT org_path FROM sys_org_unit WHERE company_id=? AND id=?",String.class,u.companyId(),orgId);for(Map<String,Object> org:db.queryForList("SELECT manager_user_id managerUserId,manager_position_id managerPositionId FROM sys_org_unit WHERE company_id=? AND deleted_at IS NULL AND status='ENABLED' AND ? LIKE CONCAT(org_path,'%') ORDER BY org_level DESC",u.companyId(),path)){Long manager=org.get("managerUserId")==null?null:((Number)org.get("managerUserId")).longValue();if(manager==null&&org.get("managerPositionId")!=null){List<Long> people=db.query("SELECT user_id FROM sys_user_position WHERE company_id=? AND position_id=? AND status='ENABLED' AND deleted_at IS NULL ORDER BY id LIMIT 1",(rs,n)->rs.getLong(1),u.companyId(),org.get("managerPositionId"));if(!people.isEmpty())manager=people.get(0);}if(manager!=null&&manager!=u.id())return manager;}}throw bad("未找到可审批的部门负责人，请联系管理员配置");}
    private long nextNumber(long companyId,String type){db.update("INSERT INTO erp_number_sequence(company_id,document_type,current_value) VALUES(?,?,1) ON DUPLICATE KEY UPDATE current_value=current_value+1",companyId,type);return db.queryForObject("SELECT current_value FROM erp_number_sequence WHERE company_id=? AND document_type=?",Long.class,companyId,type);}
    private void log(ManagementUser u,long id,String action,String from,String to,String opinion){db.update("INSERT INTO crm_sales_order_log(company_id,order_id,action,from_status,to_status,opinion,operated_by) VALUES(?,?,?,?,?,?,?)",u.companyId(),id,action,from,to,trim(opinion),u.id());}
    /** 审批意见按允许标签净化，移除脚本、样式、事件属性及其他可执行内容。 */
    static String sanitizeRichText(String html){
        return SafeRichText.sanitize(html);
    }
    private boolean admin(ManagementUser u){return u.roles().contains("COMPANY_ADMIN")||u.roles().contains("PLATFORM_ADMIN")||u.permissions().contains("*");}
    private void require(ManagementUser u,String p){if(u.companyId()==null)throw new ManagementException(HttpStatus.FORBIDDEN,"账号未关联公司");if(admin(u)||u.permissions().contains(p))return;throw new ManagementException(HttpStatus.FORBIDDEN,"当前岗位未配置操作权限："+p);}
    private void requireAny(ManagementUser u,String... permissions){if(u.companyId()==null)throw new ManagementException(HttpStatus.FORBIDDEN,"账号未关联公司");if(admin(u))return;for(String permission:permissions)if(u.permissions().contains(permission))return;throw new ManagementException(HttpStatus.FORBIDDEN,"当前岗位未配置CRM销售订单操作权限");}
    private String trim(String s){return s==null||s.isBlank()?null:s.trim();}
    private ManagementException bad(String message){return new ManagementException(HttpStatus.BAD_REQUEST,message);}
    public record OrderInput(Long customerId,String phone,String shippingAddress,String remark,Integer version,List<OrderLineInput> lines){}
    public record OrderLineInput(long productId,Long quantity){}
}
