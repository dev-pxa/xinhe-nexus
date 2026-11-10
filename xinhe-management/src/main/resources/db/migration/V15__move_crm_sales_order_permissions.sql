-- 将CRM销售订单权限从“客户管理”移到CRM模块下独立的“销售订单”菜单节点。
INSERT IGNORE INTO sys_permission (
    parent_id,
    permission_code,
    permission_name,
    permission_kind,
    module_code
)
SELECT id, 'crm:sales-orders', '销售订单', 'MENU', 'CRM'
FROM sys_permission
WHERE permission_code = 'crm';

UPDATE sys_permission
SET parent_id = (SELECT root.id FROM (
        SELECT id FROM sys_permission WHERE permission_code = 'crm'
    ) root),
    permission_name = '销售订单',
    permission_kind = 'MENU',
    module_code = 'CRM'
WHERE permission_code = 'crm:sales-orders';

UPDATE sys_permission
SET parent_id = (SELECT menu.id FROM (
        SELECT id FROM sys_permission WHERE permission_code = 'crm:sales-orders'
    ) menu),
    module_code = 'CRM'
WHERE permission_code IN (
    'crm:sales-order:view',
    'crm:sales-order:create',
    'crm:sales-order:edit',
    'crm:sales-order:withdraw',
    'crm:sales-order:approve'
);

