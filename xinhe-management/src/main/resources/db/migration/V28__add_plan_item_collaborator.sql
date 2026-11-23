-- 日/周计划项协作人：协作人在自己当期计划中只读查看，源计划归属人仍是唯一编辑人。
ALTER TABLE crm_daily_plan_item
    ADD COLUMN collaborator_user_id BIGINT UNSIGNED NULL COMMENT '协作人用户ID，可为空' AFTER customer_id,
    ADD KEY idx_crm_daily_plan_item_collaborator (company_id, collaborator_user_id, deleted_at);

ALTER TABLE crm_weekly_plan_item
    ADD COLUMN collaborator_user_id BIGINT UNSIGNED NULL COMMENT '协作人用户ID，可为空' AFTER customer_id,
    ADD KEY idx_crm_weekly_plan_item_collaborator (company_id, collaborator_user_id, deleted_at);
