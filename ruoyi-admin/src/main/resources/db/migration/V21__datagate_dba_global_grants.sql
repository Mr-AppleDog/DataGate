-- =============================================================================
-- V21: 正式化 DataGate DBA 角色与全局数据授权
--
-- 需求：AUTH-001~004、IAM 角色归属。
-- 语义：复用 role_key=dba，将内置 admin 用户绑定该角色；通过显式 GLOBAL Grant
--       授予可授权的数据动作。资源级 DENY 仍优先，导出/变更仍需独立工单。
-- =============================================================================

-- 1. 资源授权支持显式全局范围。旧数据全部为 RESOURCE，不改变既有语义。
ALTER TABLE dbg_resource_grant
    ADD COLUMN IF NOT EXISTS scope_type varchar(16) NOT NULL DEFAULT 'RESOURCE';

ALTER TABLE dbg_resource_grant
    ALTER COLUMN resource_id DROP NOT NULL;

ALTER TABLE dbg_resource_grant
    DROP CONSTRAINT IF EXISTS ck_dbg_grant_scope_resource;

ALTER TABLE dbg_resource_grant
    ADD CONSTRAINT ck_dbg_grant_scope_resource CHECK (
        (scope_type = 'RESOURCE' AND resource_id IS NOT NULL)
        OR (scope_type = 'GLOBAL' AND resource_id IS NULL)
    );

COMMENT ON COLUMN dbg_resource_grant.scope_type IS 'RESOURCE=指定资源及后代；GLOBAL=固定租户内全部数据库资源';
COMMENT ON COLUMN dbg_resource_grant.subject_type IS 'USER/DEPT/GROUP/ROLE（docs/03 第 5.2 节）';

-- 2. 将原开发环境手工种子提升为正式角色；环境已存在时复用原 role_id。
DO $$
DECLARE
    v_role_id int8;
BEGIN
    SELECT role_id INTO v_role_id
    FROM sys_role
    WHERE tenant_id = '000000' AND role_key = 'dba'
    ORDER BY role_id
    LIMIT 1;

    IF v_role_id IS NULL THEN
        IF NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 10) THEN
            v_role_id := 10;
        ELSE
            SELECT COALESCE(MAX(role_id), 10) + 1 INTO v_role_id FROM sys_role;
        END IF;

        INSERT INTO sys_role
            (role_id, tenant_id, role_name, role_key, role_sort, data_scope,
             menu_check_strictly, dept_check_strictly, status, del_flag,
             create_dept, create_by, create_time, remark)
        VALUES
            (v_role_id, '000000', 'DataGate DBA', 'dba', 2, '1',
             true, true, '0', '0', 103, 1, now(),
             'DataGate 数据库治理与全局数据访问角色');
    ELSE
        UPDATE sys_role
        SET role_name = 'DataGate DBA', status = '0', del_flag = '0',
            remark = 'DataGate 数据库治理与全局数据访问角色',
            update_by = 1, update_time = now()
        WHERE role_id = v_role_id;
    END IF;
END $$;

-- 3. 内置 admin 同时持有 superadmin 和 dba；这是用户-角色绑定，不是超管隐式放行。
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u
JOIN sys_role r ON r.tenant_id = u.tenant_id AND r.role_key = 'dba' AND r.del_flag = '0'
WHERE u.tenant_id = '000000' AND u.user_name = 'admin' AND u.del_flag = '0'
ON CONFLICT DO NOTHING;

-- 4. DBA 获得 DataGate 菜单树。功能权限与数据授权仍是两套独立检查。
WITH RECURSIVE datagate_menu AS (
    SELECT menu_id FROM sys_menu WHERE menu_id = 9200
    UNION ALL
    SELECT child.menu_id
    FROM sys_menu child
    JOIN datagate_menu parent ON child.parent_id = parent.menu_id
)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN datagate_menu m
WHERE r.tenant_id = '000000' AND r.role_key = 'dba' AND r.del_flag = '0'
ON CONFLICT DO NOTHING;

-- 5. GLOBAL Grant 为真实授权依据。ADMIN/CODE 仍无直接执行路径。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dbg_global_role_grant
    ON dbg_resource_grant (tenant_id, subject_type, subject_id, action)
    WHERE scope_type = 'GLOBAL' AND del_flag = '0' AND revoked_at IS NULL;

WITH dba_role AS (
    SELECT role_id
    FROM sys_role
    WHERE tenant_id = '000000' AND role_key = 'dba' AND status = '0' AND del_flag = '0'
    ORDER BY role_id
    LIMIT 1
),
actions(action, ordinal) AS (
    VALUES
        ('DISCOVER', 1), ('METADATA_READ', 2), ('OWNER_MANAGE', 3),
        ('SLOW_READ', 4), ('SLOW_SAMPLE_READ', 5), ('QUERY', 6),
        ('EXPLAIN', 7), ('EXPORT', 8), ('COLUMN_UNMASK', 9),
        ('CHANGE_DML', 10), ('CHANGE_DDL', 11), ('REDIS_SCAN', 12),
        ('REDIS_READ', 13), ('REDIS_WRITE', 14), ('REDIS_DELETE', 15),
        ('REDIS_ADMIN', 16)
),
next_version AS (
    SELECT COALESCE(MAX(policy_version), 0) + 1 AS value FROM dbg_resource_grant
)
INSERT INTO dbg_resource_grant
    (id, tenant_id, subject_type, subject_id, resource_id, scope_type, action,
     effect, effective_at, source_type, source_id, reason, policy_version,
     create_dept, create_by, create_time, del_flag)
SELECT
    -2100000000000000000 - actions.ordinal,
    '000000', 'ROLE', dba_role.role_id, NULL, 'GLOBAL', actions.action,
    'ALLOW', now(), 'SYSTEM', dba_role.role_id,
    'DataGate DBA 显式全局数据授权；高风险动作仍需工单', next_version.value,
    103, 1, now(), '0'
FROM dba_role
CROSS JOIN actions
CROSS JOIN next_version
ON CONFLICT DO NOTHING;
