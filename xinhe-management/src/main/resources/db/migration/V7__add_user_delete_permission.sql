-- 新增用户软删除权限；只有公司管理员或显式获得该权限的岗位可删除公司成员。
INSERT IGNORE INTO sys_permission
    (permission_code, permission_name, permission_kind, module_code)
VALUES
    ('system:user:delete', '删除用户', 'ACTION', 'SYSTEM');
