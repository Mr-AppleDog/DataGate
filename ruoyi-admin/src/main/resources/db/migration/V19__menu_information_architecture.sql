-- =============================================================================
-- V19: DataGate 左侧菜单信息架构收敛
--
-- 需求：RES-01、AUTH-01、QRY-01、SLOW-01、ALT-01、OPS-01（docs/01 §6.1）。
-- 目标：消除前端静态路由、M2 手工权限菜单和 M4/M5 Flyway 菜单叠加造成的
--       “数据库治理/数据治理”重复入口；不删除权限标识，不改变服务端鉴权语义。
-- =============================================================================

-- ---------- 1. PRD 规定的一级业务栏目 ----------
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9290, '访问控制',   0, 2, 'access-control',  '', '', '1', '0', 'M', '0', '0', '', 'lock',    103, 1, now(), NULL, NULL, '权限申请、审批与紧急访问'),
    (9291, '慢查询治理', 0, 3, 'slow-governance', '', '', '1', '0', 'M', '0', '0', '', 'monitor', 103, 1, now(), NULL, NULL, '慢查询、采集与告警治理'),
    (9292, '数据资产',   0, 4, 'data-assets',     '', '', '1', '0', 'M', '0', '0', '', 'tree',    103, 1, now(), NULL, NULL, '数据源、资源目录与列标签')
ON CONFLICT (menu_id) DO NOTHING;

-- 9200 是已发布的 DataGate 根菜单，保留主键以兼容既有角色绑定。
UPDATE sys_menu
SET menu_name = '数据工作台', parent_id = 0, order_num = 1, path = 'db',
    component = '', menu_type = 'M', visible = '0', status = '0', perms = '',
    icon = 'server', update_by = 1, update_time = now(),
    remark = '查询、导出与受控变更工作台'
WHERE menu_id = 9200;

UPDATE sys_menu
SET menu_name = CASE menu_id
        WHEN 9290 THEN '访问控制'
        WHEN 9291 THEN '慢查询治理'
        WHEN 9292 THEN '数据资产'
    END,
    parent_id = 0,
    order_num = CASE menu_id WHEN 9290 THEN 2 WHEN 9291 THEN 3 WHEN 9292 THEN 4 END,
    path = CASE menu_id
        WHEN 9290 THEN 'access-control'
        WHEN 9291 THEN 'slow-governance'
        WHEN 9292 THEN 'data-assets'
    END,
    component = '', menu_type = 'M', visible = '0', status = '0', perms = '',
    icon = CASE menu_id WHEN 9290 THEN 'lock' WHEN 9291 THEN 'monitor' ELSE 'tree' END,
    update_by = 1, update_time = now()
WHERE menu_id IN (9290, 9291, 9292);

-- ---------- 2. M2 查询控制台与权限申请改为正式动态菜单 ----------
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9293, '查询控制台', 9200, 1, 'console', 'db/console/index', '',
     '1', '0', 'C', '0', '0', 'db:console:query', 'search',
     103, 1, now(), NULL, NULL, '受控只读查询工作台'),
    (20000, '查询权限', 9290, 1, 'query-grants', 'db/workflow/index', '',
     '1', '0', 'C', '0', '0', 'db:workflow:list', 'lock',
     103, 1, now(), NULL, NULL, '查询权限申请与审批'),
    (20001, '查询权限申请', 20000, 1, '#', '', '', '1', '0', 'F', '0', '0', 'db:workflow:apply', '#', 103, 1, now(), NULL, NULL, ''),
    (20002, '审批查询权限', 20000, 2, '#', '', '', '1', '0', 'F', '0', '0', 'db:workflow:approve', '#', 103, 1, now(), NULL, NULL, ''),
    (20003, '申请单列表',   20000, 3, '#', '', '', '1', '0', 'F', '0', '0', 'db:workflow:list', '#', 103, 1, now(), NULL, NULL, ''),
    (20004, '控制台查询',   9293, 1, '#', '', '', '1', '0', 'F', '0', '0', 'db:console:query', '#', 103, 1, now(), NULL, NULL, ''),
    (20005, '控制台取消',   9293, 2, '#', '', '', '1', '0', 'F', '0', '0', 'db:console:cancel', '#', 103, 1, now(), NULL, NULL, ''),
    (20006, '数据源同步',   9292, 1, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:sync', '#', 103, 1, now(), NULL, NULL, ''),
    (20007, '数据源列表',   9292, 2, '#', '', '', '1', '0', 'F', '0', '0', 'db:datasource:list', '#', 103, 1, now(), NULL, NULL, '')
ON CONFLICT (menu_id) DO NOTHING;

-- M2 冒烟环境曾手工创建 20000-20007；这里统一修正其结构，使迁移对已有库和新库一致。
UPDATE sys_menu
SET menu_name = '查询控制台', parent_id = 9200, order_num = 1, path = 'console',
    component = 'db/console/index', query_param = '', is_frame = '1', is_cache = '0',
    menu_type = 'C', visible = '0', status = '0', perms = 'db:console:query',
    icon = 'search', update_by = 1, update_time = now(), remark = '受控只读查询工作台'
WHERE menu_id = 9293;

UPDATE sys_menu
SET menu_name = '查询权限', parent_id = 9290, order_num = 1, path = 'query-grants',
    component = 'db/workflow/index', query_param = '', is_frame = '1', is_cache = '0',
    menu_type = 'C', visible = '0', status = '0', perms = 'db:workflow:list',
    icon = 'lock', update_by = 1, update_time = now(), remark = '查询权限申请与审批'
WHERE menu_id = 20000;

UPDATE sys_menu SET parent_id = 20000, menu_type = 'F', visible = '0', status = '0', update_by = 1, update_time = now()
WHERE menu_id IN (20001, 20002, 20003);
UPDATE sys_menu SET parent_id = 9293, menu_type = 'F', visible = '0', status = '0', update_by = 1, update_time = now()
WHERE menu_id IN (20004, 20005);
UPDATE sys_menu SET parent_id = 9292, menu_type = 'F', visible = '0', status = '0', update_by = 1, update_time = now()
WHERE menu_id IN (20006, 20007);

-- ---------- 3. 按业务域归并 M4/M5 页面 ----------
UPDATE sys_menu
SET parent_id = 9200,
    order_num = CASE menu_id WHEN 9250 THEN 2 WHEN 9260 THEN 3 END,
    update_by = 1, update_time = now()
WHERE menu_id IN (9250, 9260);

UPDATE sys_menu
SET parent_id = 9290, order_num = 2, update_by = 1, update_time = now()
WHERE menu_id = 9270;

UPDATE sys_menu
SET parent_id = 9291,
    menu_name = CASE WHEN menu_id = 9201 THEN '慢查询工作台' ELSE menu_name END,
    order_num = CASE menu_id WHEN 9201 THEN 1 WHEN 9220 THEN 2 WHEN 9210 THEN 3 END,
    update_by = 1, update_time = now()
WHERE menu_id IN (9201, 9210, 9220);

-- 采集器页面尚未实现，保留权限与路由记录但不向用户展示无效入口。
UPDATE sys_menu SET visible = '1', update_by = 1, update_time = now() WHERE menu_id = 9210;

UPDATE sys_menu
SET parent_id = 9292, order_num = 1, update_by = 1, update_time = now()
WHERE menu_id = 9280;

-- ---------- 4. 收起上游框架菜单 ----------
-- 运维能力放入系统管理；未装配的系统工具、上游官网和原始工作流入口不占一级导航。
UPDATE sys_menu SET parent_id = 1, order_num = 20, update_by = 1, update_time = now() WHERE menu_id = 2;
UPDATE sys_menu SET visible = '1', update_by = 1, update_time = now() WHERE menu_id IN (3, 4);
UPDATE sys_menu SET parent_id = 9290, visible = '1', update_by = 1, update_time = now() WHERE menu_id IN (11616, 11618);
UPDATE sys_menu SET order_num = 5, update_by = 1, update_time = now() WHERE menu_id = 1;

-- 若测试库还留有同名手工根目录，隐藏而不物理删除，避免破坏既有角色绑定。
UPDATE sys_menu
SET visible = '1', status = '1', update_by = 1, update_time = now(),
    remark = 'V19 已由正式业务栏目替代'
WHERE parent_id = 0 AND menu_type = 'M' AND menu_name IN ('数据库治理', '数据治理')
  AND menu_id NOT IN (9200, 20000);

-- ---------- 5. 角色继承新父栏目 ----------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 9200), (1, 9290), (1, 9291), (1, 9292), (1, 9293),
       (1, 20000), (1, 20001), (1, 20002), (1, 20003),
       (1, 20004), (1, 20005), (1, 20006), (1, 20007)
ON CONFLICT DO NOTHING;

-- 既有角色已持有具体页面/按钮时，补齐新父栏目；不扩大其业务动作权限。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 9200 FROM sys_role_menu
WHERE menu_id IN (9250, 9260, 9293, 20004, 20005)
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 9293 FROM sys_role_menu
WHERE menu_id IN (20004, 20005)
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 9290 FROM sys_role_menu
WHERE menu_id IN (9270, 11616, 11618, 20000, 20001, 20002, 20003)
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 9291 FROM sys_role_menu
WHERE menu_id IN (9201, 9210, 9220)
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 9292 FROM sys_role_menu
WHERE menu_id = 9280
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 1 FROM sys_role_menu
WHERE menu_id = 2
ON CONFLICT DO NOTHING;
