package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ERP 首期已实现部分：公司级商品资料和销售订单部门负责人审批。 */
@Service
public class ErpService {
    private final JdbcTemplate db;
    public ErpService(JdbcTemplate db) { this.db = db; }

    public List<Map<String, Object>> products(ManagementUser actor, String productCode, String productName,
                                               String model, String specification, Long categoryId, String status) {
        require(actor, "erp:product:view");
        StringBuilder sql = new StringBuilder("SELECT p.id,p.product_code productCode,p.product_name productName,p.category_id categoryId,c.category_code categoryCode,c.category_name categoryName,p.model,p.specification,p.unit_price unitPrice,p.remark,p.status,p.version,p.created_at createdAt,p.updated_at updatedAt FROM erp_product p JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id WHERE p.company_id=?");
        List<Object> params = new ArrayList<>();
        params.add(actor.companyId());
        appendLike(sql, params, "p.product_code", productCode);
        appendLike(sql, params, "p.product_name", productName);
        appendLike(sql, params, "p.model", model);
        appendLike(sql, params, "p.specification", specification);
        if (categoryId != null) { sql.append(" AND p.category_id=?"); params.add(categoryId); }
        if (status != null && !status.isBlank()) { sql.append(" AND p.status=?"); params.add(status.trim()); }
        sql.append(" ORDER BY p.id DESC");
        return db.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> product(ManagementUser actor, long id) {
        require(actor, "erp:product:view");
        return productRow(actor.companyId(), id);
    }

    private Map<String, Object> productRow(long companyId, long id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT p.id,p.product_code productCode,p.product_name productName,p.category_id categoryId,c.category_code categoryCode,c.category_name categoryName,p.model,p.specification,p.unit_price unitPrice,p.remark,p.status,p.version,p.created_at createdAt,p.updated_at updatedAt FROM erp_product p JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id WHERE p.id=? AND p.company_id=?", id, companyId);
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "商品不存在");
        return rows.get(0);
    }

    public List<Map<String, Object>> productCategories(ManagementUser actor, Boolean enabledOnly) {
        require(actor, "erp:product:view");
        db.update("INSERT IGNORE INTO erp_product_category(company_id,parent_id,category_code,category_name,sort_order,status,created_by,updated_by) VALUES(?,NULL,'CAT-0000','未分类',0,'ENABLED',?,?)", actor.companyId(), actor.id(), actor.id());
        String enabled = Boolean.TRUE.equals(enabledOnly) ? " AND c.status='ENABLED'" : "";
        return db.queryForList("SELECT c.id,c.parent_id parentId,c.category_code categoryCode,c.category_name categoryName,c.status,c.version,(SELECT COUNT(*) FROM erp_product p WHERE p.company_id=c.company_id AND p.category_id=c.id) productCount FROM erp_product_category c WHERE c.company_id=?" + enabled + " ORDER BY c.parent_id IS NOT NULL,c.parent_id,c.id", actor.companyId());
    }

    @Transactional
    public Map<String, Object> createProductCategory(ManagementUser actor, ProductCategoryInput body) {
        require(actor, "erp:product:edit");
        String name = required(body.categoryName(), "分类名称");
        validateParentCategory(actor.companyId(), body.parentId(), null);
        ensureCategoryNameAvailable(actor.companyId(), body.parentId(), name, null);
        long sequence = nextNumber(actor.companyId(), "PRODUCT_CATEGORY");
        String code = "CAT-" + String.format("%04d", sequence);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(c -> { var ps = c.prepareStatement("INSERT INTO erp_product_category(company_id,parent_id,category_code,category_name,created_by,updated_by) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS); ps.setLong(1, actor.companyId()); if (body.parentId() == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, body.parentId()); ps.setString(3, code); ps.setString(4, name); ps.setLong(5, actor.id()); ps.setLong(6, actor.id()); return ps; }, key);
        return category(actor.companyId(), key.getKey().longValue());
    }

    @Transactional
    public Map<String, Object> updateProductCategory(ManagementUser actor, long id, ProductCategoryInput body) {
        require(actor, "erp:product:edit");
        String name = required(body.categoryName(), "分类名称");
        validateParentCategory(actor.companyId(), body.parentId(), id);
        ensureCategoryNameAvailable(actor.companyId(), body.parentId(), name, id);
        int changed = db.update("UPDATE erp_product_category SET parent_id=?,category_name=?,version=version+1,updated_by=? WHERE id=? AND company_id=?", body.parentId(), name, actor.id(), id, actor.companyId());
        if (changed == 0) throw new ManagementException(HttpStatus.NOT_FOUND, "商品分类不存在");
        return category(actor.companyId(), id);
    }

    @Transactional
    public void setProductCategoryStatus(ManagementUser actor, long id, boolean enabled) {
        require(actor, "erp:product:edit");
        if (!enabled) {
            Integer children = db.queryForObject("SELECT COUNT(*) FROM erp_product_category WHERE company_id=? AND parent_id=? AND status='ENABLED'", Integer.class, actor.companyId(), id);
            if (children != null && children > 0) throw bad("请先停用该分类下的子分类");
        }
        int changed = db.update("UPDATE erp_product_category SET status=?,version=version+1,updated_by=? WHERE id=? AND company_id=?", enabled ? "ENABLED" : "DISABLED", actor.id(), id, actor.companyId());
        if (changed == 0) throw new ManagementException(HttpStatus.NOT_FOUND, "商品分类不存在");
    }

    @Transactional
    public Map<String, Object> createProduct(ManagementUser actor, ProductInput body) {
        require(actor, "erp:product:create");
        String name = required(body.productName(), "商品名称");
        BigDecimal price = body.unitPrice() == null ? BigDecimal.ZERO : body.unitPrice();
        if (price.signum() < 0) throw bad("商品单价不能小于0");
        requireActiveCategory(actor.companyId(), body.categoryId());
        String code = "PRD-" + String.format("%04d", nextNumber(actor.companyId(), "PRODUCT"));
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(c -> { var ps = c.prepareStatement("INSERT INTO erp_product(company_id,product_code,product_name,category_id,model,specification,unit_price,remark,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS); ps.setLong(1, actor.companyId()); ps.setString(2, code); ps.setString(3, name); ps.setLong(4, body.categoryId()); ps.setString(5, blank(body.model())); ps.setString(6, blank(body.specification())); ps.setBigDecimal(7, price); ps.setString(8, blank(body.remark())); ps.setLong(9, actor.id()); ps.setLong(10, actor.id()); return ps; }, key);
        long id = key.getKey().longValue();
        return productRow(actor.companyId(), id);
    }

    @Transactional
    public Map<String, Object> updateProduct(ManagementUser actor, long id, ProductInput body) {
        require(actor, "erp:product:edit");
        BigDecimal price = body.unitPrice() == null ? BigDecimal.ZERO : body.unitPrice();
        if (price.signum() < 0) throw bad("商品单价不能小于0");
        requireActiveCategory(actor.companyId(), body.categoryId());
        int changed = db.update("UPDATE erp_product SET product_name=?,category_id=?,model=?,specification=?,unit_price=?,remark=?,version=version+1,updated_by=? WHERE id=? AND company_id=? AND version=?", required(body.productName(), "商品名称"), body.categoryId(), blank(body.model()), blank(body.specification()), price, blank(body.remark()), actor.id(), id, actor.companyId(), body.version() == null ? -1 : body.version());
        if (changed == 0) throw new ManagementException(HttpStatus.CONFLICT, "商品已被其他人修改，请刷新后重试");
        return productRow(actor.companyId(), id);
    }

    @Transactional
    public void setProductStatus(ManagementUser actor, long id, boolean enabled) {
        require(actor, "erp:product:status");
        int changed = db.update("UPDATE erp_product SET status=?,version=version+1,updated_by=? WHERE id=? AND company_id=?", enabled ? "ENABLED" : "DISABLED", actor.id(), id, actor.companyId());
        if (changed == 0) throw new ManagementException(HttpStatus.NOT_FOUND, "商品不存在");
    }

    public List<Map<String, Object>> orders(ManagementUser actor, boolean pendingOnly) {
        require(actor, "erp:sales-order:view");
        if (pendingOnly) {
            require(actor, "erp:sales-order:approve");
            return db.queryForList("SELECT o.id,o.application_no applicationNo,o.order_no orderNo,o.customer_name customerName,o.status,o.total_amount totalAmount,o.created_at createdAt,u.display_name creatorName FROM erp_sales_order o JOIN sys_user u ON u.id=o.created_by WHERE o.company_id=? AND o.approver_user_id=? AND o.created_by<>? AND o.status='PENDING_APPROVAL' ORDER BY o.created_at", actor.companyId(), actor.id(), actor.id());
        }
        return db.queryForList("SELECT o.id,o.application_no applicationNo,o.order_no orderNo,o.customer_name customerName,o.status,o.total_amount totalAmount,o.created_at createdAt,o.approval_comment approvalComment,u.display_name creatorName FROM erp_sales_order o JOIN sys_user u ON u.id=o.created_by WHERE o.company_id=? AND (o.created_by=? OR o.approver_user_id=?) ORDER BY o.created_at DESC", actor.companyId(), actor.id(), actor.id());
    }

    /** 订单详情仅向申请人和当前审批负责人开放，供详情页完整展示审核依据。 */
    public Map<String, Object> orderDetail(ManagementUser actor, long id) {
        if (actor.companyId() == null) throw new ManagementException(HttpStatus.FORBIDDEN, "账号未关联公司");
        if (!actor.roles().contains("COMPANY_ADMIN") && !actor.roles().contains("PLATFORM_ADMIN")
                && !actor.permissions().contains("*") && !actor.permissions().contains("erp:sales-order:view")
                && !actor.permissions().contains("erp:sales-order:approve")
                && !actor.permissions().contains("erp:sales-order:create")) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "没有查看订单的权限");
        }
        List<Map<String, Object>> matches = db.queryForList("SELECT o.id,o.application_no applicationNo,o.order_no orderNo,o.customer_name customerName,o.contact_name contactName,o.phone,o.shipping_address shippingAddress,o.expected_delivery_date expectedDeliveryDate,o.status,o.total_amount totalAmount,o.remark,o.approval_comment approvalComment,o.created_at createdAt,o.approved_at approvedAt,o.created_by creatorUserId,o.approver_user_id approverUserId,u.display_name creatorName FROM erp_sales_order o JOIN sys_user u ON u.id=o.created_by WHERE o.id=? AND o.company_id=? AND (o.created_by=? OR o.approver_user_id=?)", id, actor.companyId(), actor.id(), actor.id());
        if (matches.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "订单不存在或无权查看");
        Map<String, Object> order = matches.get(0);
        order.put("lines", db.queryForList("SELECT line_no lineNo,product_id productId,product_code_snapshot productCode,product_name_snapshot productName,category_name_snapshot categoryName,product_model_snapshot model,product_specification_snapshot specification,quantity,unit_price unitPrice,amount FROM erp_sales_order_line WHERE order_id=? AND company_id=? ORDER BY line_no", id, actor.companyId()));
        return order;
    }

    @Transactional
    public Map<String, Object> submitOrder(ManagementUser actor, OrderInput body) {
        require(actor, "erp:sales-order:create");
        String customer = required(body.customerName(), "客户名称");
        String phone = required(body.phone(), "联系电话");
        String shippingAddress = required(body.shippingAddress(), "收货地址");
        if (body.lines() == null || body.lines().isEmpty()) throw bad("请至少添加一条商品明细");
        long approver = departmentApprover(actor);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(c -> { var ps = c.prepareStatement("INSERT INTO erp_sales_order(company_id,application_no,customer_name,contact_name,phone,shipping_address,expected_delivery_date,status,total_amount,remark,approver_user_id,created_by,updated_by) VALUES(?,'PENDING',?,?,?,?,?,'PENDING_APPROVAL',0,?,?,?,?)", Statement.RETURN_GENERATED_KEYS); ps.setLong(1, actor.companyId()); ps.setString(2, customer); ps.setString(3, blank(body.contactName())); ps.setString(4, phone); ps.setString(5, shippingAddress); ps.setObject(6, body.expectedDeliveryDate()); ps.setString(7, blank(body.remark())); ps.setLong(8, approver); ps.setLong(9, actor.id()); ps.setLong(10, actor.id()); return ps; }, key);
        long id = key.getKey().longValue();
        String appNo = "APP-" + String.format("%08d", id);
        db.update("UPDATE erp_sales_order SET application_no=? WHERE id=?", appNo, id);
        BigDecimal total = BigDecimal.ZERO;
        int lineNo = 1;
        for (OrderLineInput line : body.lines()) {
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().stripTrailingZeros().scale() > 0) throw bad("订购数量必须为正整数");
            Map<String, Object> product = db.queryForMap("SELECT p.product_code,p.product_name,c.category_name,p.model,p.specification,p.unit_price FROM erp_product p JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id WHERE p.id=? AND p.company_id=? AND p.status='ENABLED'", line.productId(), actor.companyId());
            BigDecimal price = line.unitPrice() == null ? (BigDecimal) product.get("unit_price") : line.unitPrice();
            if (price.signum() < 0) throw bad("商品单价不能小于0");
            BigDecimal amount = line.quantity().multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);
            total = total.add(amount);
            db.update("INSERT INTO erp_sales_order_line(company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", actor.companyId(), id, lineNo++, line.productId(), product.get("product_code"), product.get("product_name"), product.get("category_name"), product.get("model"), product.get("specification"), line.quantity(), price, amount);
        }
        db.update("UPDATE erp_sales_order SET total_amount=? WHERE id=?", total, id);
        db.update("INSERT INTO erp_sales_order_approval(company_id,order_id,approver_user_id) VALUES(?,?,?)", actor.companyId(), id, approver);
        return db.queryForMap("SELECT id,application_no applicationNo,status,total_amount totalAmount,approver_user_id approverUserId FROM erp_sales_order WHERE id=?", id);
    }

    /** 申请人根据驳回建议修改原申请后重新提交，保留申请编号并重置原审批任务。 */
    @Transactional
    public Map<String, Object> resubmitOrder(ManagementUser actor, long id, OrderInput body) {
        require(actor, "erp:sales-order:create");
        Map<String, Object> existing = db.queryForMap("SELECT status,approver_user_id approverUserId FROM erp_sales_order WHERE id=? AND company_id=? AND created_by=? FOR UPDATE", id, actor.companyId(), actor.id());
        if (!"REJECTED".equals(existing.get("status"))) throw bad("只有被驳回的订单申请可以修改并重新提交");
        String customer = required(body.customerName(), "客户名称");
        String phone = required(body.phone(), "联系电话");
        String shippingAddress = required(body.shippingAddress(), "收货地址");
        if (body.lines() == null || body.lines().isEmpty()) throw bad("请至少添加一条商品明细");
        BigDecimal total = BigDecimal.ZERO;
        int lineNo = 1;
        db.update("DELETE FROM erp_sales_order_line WHERE company_id=? AND order_id=?", actor.companyId(), id);
        for (OrderLineInput line : body.lines()) {
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().stripTrailingZeros().scale() > 0) throw bad("订购数量必须为正整数");
            Map<String, Object> product = db.queryForMap("SELECT p.product_code,p.product_name,c.category_name,p.model,p.specification,p.unit_price FROM erp_product p JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id WHERE p.id=? AND p.company_id=? AND p.status='ENABLED'", line.productId(), actor.companyId());
            BigDecimal price = (BigDecimal) product.get("unit_price");
            BigDecimal amount = line.quantity().multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);
            total = total.add(amount);
            db.update("INSERT INTO erp_sales_order_line(company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", actor.companyId(), id, lineNo++, line.productId(), product.get("product_code"), product.get("product_name"), product.get("category_name"), product.get("model"), product.get("specification"), line.quantity(), price, amount);
        }
        db.update("UPDATE erp_sales_order SET customer_name=?,contact_name=?,phone=?,shipping_address=?,expected_delivery_date=?,remark=?,total_amount=?,status='PENDING_APPROVAL',updated_by=?,version=version+1 WHERE id=? AND company_id=?", customer, blank(body.contactName()), phone, shippingAddress, body.expectedDeliveryDate(), blank(body.remark()), total, actor.id(), id, actor.companyId());
        db.update("UPDATE erp_sales_order_approval SET approver_user_id=?,decision='PENDING',opinion=NULL,decided_at=NULL,created_at=NOW() WHERE company_id=? AND order_id=?", existing.get("approverUserId"), actor.companyId(), id);
        return db.queryForMap("SELECT id,application_no applicationNo,status,total_amount totalAmount,approver_user_id approverUserId FROM erp_sales_order WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> decideOrder(ManagementUser actor, long id, boolean approve, String opinion) {
        require(actor, "erp:sales-order:approve");
        Map<String, Object> order = db.queryForMap("SELECT id,status,created_by creatorUserId,approver_user_id approverUserId FROM erp_sales_order WHERE id=? AND company_id=? FOR UPDATE", id, actor.companyId());
        if (order.get("creatorUserId") instanceof Number creator && creator.longValue() == actor.id()) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "申请人不能审批自己的订单");
        }
        if (!"PENDING_APPROVAL".equals(order.get("status")) || ((Number) order.get("approverUserId")).longValue() != actor.id()) throw new ManagementException(HttpStatus.FORBIDDEN, "订单不在你的待审核列表中");
        db.update("UPDATE erp_sales_order SET status=?,approval_comment=?,approved_at=IF(?,NOW(),NULL),updated_by=?,version=version+1 WHERE id=?", approve ? "OPEN" : "REJECTED", blank(opinion), approve, actor.id(), id);
        db.update("UPDATE erp_sales_order_approval SET decision=?,opinion=?,decided_at=NOW() WHERE company_id=? AND order_id=? AND approver_user_id=? AND decision='PENDING'", approve ? "APPROVED" : "REJECTED", blank(opinion), actor.companyId(), id, actor.id());
        if (approve) {
            String orderNo = "SO-" + String.format("%08d", id);
            db.update("UPDATE erp_sales_order SET order_no=? WHERE id=? AND company_id=?", orderNo, id, actor.companyId());
        }
        return db.queryForMap("SELECT id,application_no applicationNo,order_no orderNo,status,approval_comment approvalComment FROM erp_sales_order WHERE id=?", id);
    }

    private long departmentApprover(ManagementUser actor) {
        var manager = OrganizationApprovers.nearest(db, actor.companyId(), actor.id(), "erp:sales-order:approve");
        if (manager == null) throw bad("本部门及上级组织未找到有效且有订单审批权限的负责人，请联系管理员配置");
        return ((Number)manager.get("id")).longValue();
    }

    private void appendLike(StringBuilder sql, List<Object> params, String column, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append(" LIKE CONCAT('%',?,'%')");
        params.add(value.trim());
    }

    private long nextNumber(long companyId, String documentType) {
        db.update("INSERT INTO erp_number_sequence(company_id,document_type,current_value) VALUES(?,?,1) ON DUPLICATE KEY UPDATE current_value=current_value+1", companyId, documentType);
        Long value = db.queryForObject("SELECT current_value FROM erp_number_sequence WHERE company_id=? AND document_type=?", Long.class, companyId, documentType);
        if (value == null) throw new IllegalStateException("业务编号生成失败");
        return value;
    }

    private Map<String, Object> category(long companyId, long id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,parent_id parentId,category_code categoryCode,category_name categoryName,status,version FROM erp_product_category WHERE id=? AND company_id=?", id, companyId);
        if (rows.isEmpty()) throw new ManagementException(HttpStatus.NOT_FOUND, "商品分类不存在");
        return rows.get(0);
    }

    private void validateParentCategory(long companyId, Long parentId, Long currentId) {
        if (parentId == null) return;
        if (currentId != null && currentId.equals(parentId)) throw bad("商品分类不能选择自身作为上级分类");
        List<Map<String, Object>> parents = db.queryForList("SELECT parent_id parentId,status FROM erp_product_category WHERE id=? AND company_id=?", parentId, companyId);
        if (parents.isEmpty() || !"ENABLED".equals(parents.get(0).get("status"))) throw bad("上级分类不存在或已停用");
        if (parents.get(0).get("parentId") != null) throw bad("商品分类最多支持两级");
    }

    private void ensureCategoryNameAvailable(long companyId, Long parentId, String name, Long excludeId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM erp_product_category WHERE company_id=? AND ((parent_id IS NULL AND ? IS NULL) OR parent_id=?) AND category_name=? AND (? IS NULL OR id<>?)", Integer.class, companyId, parentId, parentId, name, excludeId, excludeId);
        if (count != null && count > 0) throw bad("同级分类名称已存在");
    }

    private void requireActiveCategory(long companyId, Long categoryId) {
        if (categoryId == null) throw bad("商品分类不能为空");
        Integer count = db.queryForObject("SELECT COUNT(*) FROM erp_product_category WHERE id=? AND company_id=? AND status='ENABLED'", Integer.class, categoryId, companyId);
        if (count == null || count == 0) throw bad("商品分类不存在或已停用");
    }

    private void require(ManagementUser u, String permission) {
        if (u.companyId() == null) throw new ManagementException(HttpStatus.FORBIDDEN, "账号未关联公司");
        if (u.roles().contains("COMPANY_ADMIN") || u.roles().contains("PLATFORM_ADMIN") || u.permissions().contains("*") || u.permissions().contains(permission)) return;
        throw new ManagementException(HttpStatus.FORBIDDEN, "没有执行该操作的权限");
    }
    private String required(String s, String label) { if (s == null || s.isBlank()) throw bad(label + "不能为空"); return s.trim(); }
    private String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private ManagementException bad(String s) { return new ManagementException(HttpStatus.BAD_REQUEST, s); }

    public record ProductInput(String productName, Long categoryId, String model, String specification, BigDecimal unitPrice, String remark, Integer version) {}
    public record ProductCategoryInput(String categoryName, Long parentId) {}
    public record OrderInput(String customerName, String contactName, String phone, String shippingAddress, java.time.LocalDate expectedDeliveryDate, String remark, List<OrderLineInput> lines) {}
    public record OrderLineInput(long productId, BigDecimal quantity, BigDecimal unitPrice) {}
}
