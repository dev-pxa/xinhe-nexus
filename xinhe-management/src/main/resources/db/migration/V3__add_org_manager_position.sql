ALTER TABLE sys_org_unit
    ADD COLUMN manager_position_id BIGINT UNSIGNED NULL COMMENT '组织负责人岗位ID，必须属于当前组织' AFTER manager_user_id,
    ADD KEY idx_org_manager_position (company_id, manager_position_id);
