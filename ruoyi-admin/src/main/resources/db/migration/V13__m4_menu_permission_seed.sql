-- =============================================================================
-- V13: M4 菜单与权限 seed — 慢查询治理 + 告警管理（docs/05 §2.9）
--
-- 父菜单 9200 数据库治理；慢查询治理 9201 + 采集器 9210；告警管理 9220（规则/事件/通道）。
-- 权限按钮 db:slow:* / db:alert:* 与 Controller @SaCheckPermission 对应。
-- 固定主键 9200–9249 避免与 workflow 11600s 冲突。授权给超管 role 1。
-- =============================================================================

-- 父菜单：数据库治理
INSERT INTO sys_menu VALUES ('9200', '数据库治理', '0', '7', 'db', '', '', '1', '0', 'M', '0', '0', '', 'component', 103, 1, now(), NULL, NULL, '数据库访问治理与慢查询治理') ON CONFLICT (menu_id) DO NOTHING;

-- 慢查询治理工作台
INSERT INTO sys_menu VALUES ('9201', '慢查询治理', '9200', '1', 'slowquery', 'db/slowquery/index', '', '1', '0', 'C', '0', '0', 'db:slow:list', 'monitor', 103, 1, now(), NULL, NULL, '慢查询指纹治理工作台') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9202', '指纹查询', '9201', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:slow:query', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9203', '指纹认领', '9201', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:slow:claim', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9204', '指纹流转', '9201', '3', '#', '', '', '1', '0', 'F', '0', '0', 'db:slow:transition', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9205', '指纹评论', '9201', '4', '#', '', '', '1', '0', 'F', '0', '0', 'db:slow:comment', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- 采集器管理
INSERT INTO sys_menu VALUES ('9210', '采集器', '9200', '2', 'slow-collectors', 'db/slowcollectors/index', '', '1', '0', 'C', '0', '0', 'db:slow:collector:list', 'tool', 103, 1, now(), NULL, NULL, '慢查询采集器状态') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9211', '采集器列表', '9210', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:slow:collector:list', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9212', '手动触发采集', '9210', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:slow:collector:run', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- 告警管理（规则/事件/通道三 tab）
INSERT INTO sys_menu VALUES ('9220', '告警管理', '9200', '3', 'alert', 'db/alert/index', '', '1', '0', 'C', '0', '0', 'db:alert:event:list', 'bell', 103, 1, now(), NULL, NULL, '告警规则/事件/通知通道') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9221', '事件列表', '9220', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:event:list', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9222', '事件确认', '9220', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:event:ack', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9223', '事件静默', '9220', '3', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:event:silence', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9230', '规则列表', '9220', '4', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:rule:list', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9231', '规则新增', '9220', '5', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:rule:add', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9232', '规则修改', '9220', '6', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:rule:edit', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9233', '规则测试', '9220', '7', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:rule:test', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9240', '通道列表', '9220', '8', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:channel:list', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9241', '通道新增', '9220', '9', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:channel:add', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9242', '通道修改', '9220', '10', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:channel:edit', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9243', '通道测试', '9220', '11', '#', '', '', '1', '0', 'F', '0', '0', 'db:alert:channel:test', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- 授权给超管角色（role_id=1）
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 9200), (1, 9201), (1, 9202), (1, 9203), (1, 9204), (1, 9205),
       (1, 9210), (1, 9211), (1, 9212),
       (1, 9220), (1, 9221), (1, 9222), (1, 9223),
       (1, 9230), (1, 9231), (1, 9232), (1, 9233),
       (1, 9240), (1, 9241), (1, 9242), (1, 9243)
ON CONFLICT DO NOTHING;
