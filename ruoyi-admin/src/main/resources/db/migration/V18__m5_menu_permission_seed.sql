-- =============================================================================
-- V18: M5 菜单与权限 seed — 导出/变更/紧急访问/列脱敏（docs/05 §2.7/§2.8、docs/03 §10）
--
-- 父菜单 9200 数据库治理；导出管理 9250；变更工单 9260；紧急访问 9270；列脱敏标签 9280。
-- 权限按钮与 Controller @SaCheckPermission 对应（db:export:* / db:change:* / db:emergency:* / db:column:*）。
-- 固定主键 9250–9299 避免与 M4 9200s 冲突。授权给超管 role 1。
-- =============================================================================

-- 导出管理
INSERT INTO sys_menu VALUES ('9250', '导出管理', '9200', '4', 'export', 'db/export/index', '', '1', '0', 'C', '0', '0', 'db:export:query', 'download', 103, 1, now(), NULL, NULL, '受控导出工单') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9251', '导出申请', '9250', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:export:apply', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9252', '导出审批', '9250', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:export:approve', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9253', '导出下载', '9250', '3', '#', '', '', '1', '0', 'F', '0', '0', 'db:export:download', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9254', '导出查询', '9250', '4', '#', '', '', '1', '0', 'F', '0', '0', 'db:export:query', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- SQL 变更工单
INSERT INTO sys_menu VALUES ('9260', '变更工单', '9200', '5', 'change', 'db/change/index', '', '1', '0', 'C', '0', '0', 'db:change:query', 'edit', 103, 1, now(), NULL, NULL, 'DML/DDL/Redis 变更工单') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9261', '变更申请', '9260', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:change:apply', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9262', '变更审批', '9260', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:change:approve', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9263', '变更执行', '9260', '3', '#', '', '', '1', '0', 'F', '0', '0', 'db:change:execute', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9264', '变更查询', '9260', '4', '#', '', '', '1', '0', 'F', '0', '0', 'db:change:query', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- 紧急访问
INSERT INTO sys_menu VALUES ('9270', '紧急访问', '9200', '6', 'emergency', 'db/emergency/index', '', '1', '0', 'C', '0', '0', 'db:emergency:query', 'alert', 103, 1, now(), NULL, NULL, '双人审批临时授权') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9271', '紧急申请', '9270', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:emergency:apply', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9272', '紧急审批', '9270', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:emergency:approve', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9273', '紧急撤销', '9270', '3', '#', '', '', '1', '0', 'F', '0', '0', 'db:emergency:revoke', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9274', '紧急查询', '9270', '4', '#', '', '', '1', '0', 'F', '0', '0', 'db:emergency:query', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- 列脱敏标签管理
INSERT INTO sys_menu VALUES ('9280', '列脱敏标签', '9200', '7', 'column-profile', 'db/column-profile/index', '', '1', '0', 'C', '0', '0', 'db:column:query', 'eye', 103, 1, now(), NULL, NULL, '列敏感等级与脱敏类型人工标注') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9281', '列标签查询', '9280', '1', '#', '', '', '1', '0', 'F', '0', '0', 'db:column:query', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu VALUES ('9282', '列标签标注', '9280', '2', '#', '', '', '1', '0', 'F', '0', '0', 'db:column:mask', '#', 103, 1, now(), NULL, NULL, '') ON CONFLICT (menu_id) DO NOTHING;

-- 授权给超管角色（role_id=1）
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 9250), (1, 9251), (1, 9252), (1, 9253), (1, 9254),
       (1, 9260), (1, 9261), (1, 9262), (1, 9263), (1, 9264),
       (1, 9270), (1, 9271), (1, 9272), (1, 9273), (1, 9274),
       (1, 9280), (1, 9281), (1, 9282)
ON CONFLICT DO NOTHING;
