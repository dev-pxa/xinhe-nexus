INSERT INTO sys_permission (permission_code, permission_name, permission_kind, module_code)
SELECT 'hr:profile:approve', '人员档案审批', 'ACTION', 'OA'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'hr:profile:approve'
);

UPDATE sys_permission
SET permission_name = '人员档案审批',
    module_code = 'OA',
    permission_kind = 'ACTION'
WHERE permission_code = 'hr:profile:approve'
  AND deleted_at IS NULL;
