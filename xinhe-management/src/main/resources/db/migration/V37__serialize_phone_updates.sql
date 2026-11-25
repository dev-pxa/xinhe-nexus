CREATE TABLE sys_user_phone_guard (
    id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB COMMENT='手机号唯一性校验事务锁';
INSERT INTO sys_user_phone_guard(id) VALUES (1);
