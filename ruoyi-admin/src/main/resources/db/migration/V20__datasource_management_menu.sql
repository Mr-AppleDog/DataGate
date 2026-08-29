-- =============================================================================
-- V20: 数据资产 / 数据源管理菜单与功能权限
--
-- 需求：RES-001~005、CRED-001~004、CRED-007（docs/01 §5.2/5.3，docs/10 M1-03）。
-- 数据影响：只新增/调整 sys_menu 与 sys_role_menu，不删除业务数据，不改变资源授权。
-- 回滚说明：应用版本回滚时可将 20100 页面设为隐藏；保留按钮权限记录以兼容审计与角色历史。
-- =============================================================================

-- 页面使用独立 manage 权限，避免仅因查询控制台需要 db:datasource:list 就暴露管理入口。
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (20100, '数据源管理', 9292, 1, 'datasources', 'db/datasource/index', '',
     '1', '0', 'C', '0', '0', 'db:datasource:manage', 'server',
     103, 1, now(), NULL, NULL, 'RES-001~005：结构化数据源、连接验证、启停与元数据同步')
ON CONFLICT (menu_id) DO NOTHING;

UPDATE sys_menu
SET menu_name = '数据源管理', parent_id = 9292, order_num = 1,
    path = 'datasources', component = 'db/datasource/index', query_param = '',
    is_frame = '1', is_cache = '0', menu_type = 'C', visible = '0', status = '0',
    perms = 'db:datasource:manage', icon = 'server', update_by = 1, update_time = now(),
    remark = 'RES-001~005：结构化数据源、连接验证、启停与元数据同步'
WHERE menu_id = 20100;

-- V19 已创建的列表/同步权限迁入数据源管理页面，保留主键和既有角色绑定。
UPDATE sys_menu
SET parent_id = 20100,
    order_num = CASE menu_id WHEN 20007 THEN 1 WHEN 20006 THEN 8 END,
    menu_name = CASE menu_id WHEN 20007 THEN '数据源列表' WHEN 20006 THEN '同步元数据' END,
    menu_type = 'F', visible = '0', status = '0', update_by = 1, update_time = now()
WHERE menu_id IN (20006, 20007);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (20101, '数据源详情', 20100, 2, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:query',   '#', 103, 1, now(), NULL, NULL, 'RES-002'),
    (20102, '新增数据源', 20100, 3, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:add',     '#', 103, 1, now(), NULL, NULL, 'RES-002/004'),
    (20103, '编辑数据源', 20100, 4, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:edit',     '#', 103, 1, now(), NULL, NULL, 'RES-002/004'),
    (20104, '测试连接',   20100, 5, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:verify',   '#', 103, 1, now(), NULL, NULL, 'RES-004'),
    (20105, '启用数据源', 20100, 6, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:enable',   '#', 103, 1, now(), NULL, NULL, 'RES-004/007'),
    (20106, '停用数据源', 20100, 7, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:disable',  '#', 103, 1, now(), NULL, NULL, 'RES-007'),
    (20107, '凭据列表',   20100, 9, '#', '', '', '1', '0', 'F', '0', '0', 'db:credential:list',     '#', 103, 1, now(), NULL, NULL, 'CRED-004'),
    (20108, '新增凭据',   20100, 10, '#', '', '', '1', '0', 'F', '0', '0', 'db:credential:add',     '#', 103, 1, now(), NULL, NULL, 'CRED-001~004/007'),
    (20109, '禁用凭据',   20100, 11, '#', '', '', '1', '0', 'F', '0', '0', 'db:credential:disable', '#', 103, 1, now(), NULL, NULL, 'CRED-007')
ON CONFLICT (menu_id) DO NOTHING;

-- 修正可能由重复开发迁移留下的字段，确保新库与升级库结构一致。
UPDATE sys_menu
SET parent_id = 20100, menu_type = 'F', visible = '0', status = '0',
    path = '#', component = '', query_param = '', is_frame = '1', is_cache = '0',
    update_by = 1, update_time = now()
WHERE menu_id BETWEEN 20101 AND 20109;

-- 列脱敏标签在数据资产中排到数据源管理之后。
UPDATE sys_menu SET parent_id = 9292, order_num = 2, update_by = 1, update_time = now()
WHERE menu_id = 9280;

-- 超级管理员获得完整管理能力。
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 20100), (1, 20101), (1, 20102), (1, 20103), (1, 20104),
    (1, 20105), (1, 20106), (1, 20107), (1, 20108), (1, 20109)
ON CONFLICT DO NOTHING;

-- 已持有“同步元数据”的管理角色补齐页面与父目录；仅有列表权限的查询用户不自动获得管理入口。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 9292 FROM sys_role_menu WHERE menu_id = 20006
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 20100 FROM sys_role_menu WHERE menu_id = 20006
ON CONFLICT DO NOTHING;
