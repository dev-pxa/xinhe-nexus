ALTER TABLE oa_expense_claim
    ADD COLUMN approval_round INT NOT NULL DEFAULT 0 COMMENT '当前审批轮次' AFTER current_reviewer_id;

ALTER TABLE oa_expense_approval_node
    DROP INDEX uk_expense_approval_sequence,
    ADD COLUMN approval_round INT NOT NULL DEFAULT 1 COMMENT '审批轮次' AFTER claim_id,
    ADD COLUMN node_type VARCHAR(32) NOT NULL DEFAULT 'DEPARTMENT_MANAGER' COMMENT '节点类型：DEPARTMENT_MANAGER直属负责人、FINAL_APPROVER最终审批' AFTER sequence_no,
    ADD COLUMN org_name_snapshot VARCHAR(128) NULL COMMENT '审批时组织名称快照' AFTER org_unit_id,
    ADD COLUMN reviewer_name_snapshot VARCHAR(128) NULL COMMENT '审批人名称快照' AFTER reviewer_user_id,
    ADD COLUMN reviewer_position_name_snapshot VARCHAR(128) NULL COMMENT '审批岗位名称快照' AFTER reviewer_position_id,
    ADD UNIQUE KEY uk_expense_approval_round_sequence (claim_id, approval_round, sequence_no),
    ADD KEY idx_expense_approval_round (claim_id, approval_round, status);

UPDATE oa_expense_claim c
SET c.approval_round = CASE
    WHEN EXISTS (SELECT 1 FROM oa_expense_approval_node n WHERE n.claim_id = c.id) THEN 1
    ELSE 0
END;

UPDATE oa_expense_approval_node n
LEFT JOIN sys_org_unit o ON o.id = n.org_unit_id
LEFT JOIN sys_user u ON u.id = n.reviewer_user_id
LEFT JOIN sys_position p ON p.id = n.reviewer_position_id
SET n.approval_round = 1,
    n.org_name_snapshot = o.org_name,
    n.reviewer_name_snapshot = u.display_name,
    n.reviewer_position_name_snapshot = p.position_name;

UPDATE sys_permission
SET permission_name = '报销最终审批'
WHERE permission_code = 'oa:expense:approve';
