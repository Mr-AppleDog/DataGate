-- V10 查询权限审批流程定义（dbg_query_grant: 开始 → 申请人 → 审批人 → 结束）
-- docs/03 §10.1：申请人 → 资源 Owner 或 DBA → 自动创建 Grant
-- 流程定义供 WarmFlow 使用；固定主键 9001–9008 避免与运行时雪花 ID 冲突。
-- 申请人节点（node_code=apply）为流程定义的第一个中间节点（node_type=1），
-- 由 FlwCommonService.applyNodeCode 识别为申请人节点。
-- 审批节点（node_code=approve）permission_flag 留空，运行时由申请人在流程变量
-- PASS:approve 中指定目标审批人 userId，WarmFlow assignment listener 据此锁定办理人。
-- 未指定则审批节点无人可办——失败关闭，不误放行。

INSERT INTO flow_definition
    (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, activity_status, ext, create_time, create_by, del_flag, tenant_id)
VALUES
    (9001, 'dbg_query_grant', '查询权限申请', 'CLASSICS', 'datagate', '1', 1, 'N', 1, NULL, now(), 'datagate', '0', '000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, "version", ext, del_flag, tenant_id, create_time)
VALUES
    (9002, 0, 9001, 'start',   '开始',   NULL,    '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now()),
    (9003, 1, 9001, 'apply',   '申请人', '',       '0.000', '360,200|360,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now()),
    (9004, 1, 9001, 'approve', '审批人', '',       '0.000', '540,200|540,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9005, 2, 9001, 'end',     '结束',   NULL,    '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_skip
    (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, del_flag, tenant_id, create_time)
VALUES
    (9006, 9001, 'start',   0, 'apply',   1, NULL, 'PASS', NULL, '220,200;310,200', '0', '000000', now()),
    (9007, 9001, 'apply',   1, 'approve', 1, NULL, 'PASS', NULL, '410,200;490,200', '0', '000000', now()),
    (9008, 9001, 'approve', 1, 'end',     2, NULL, 'PASS', NULL, '590,200;670,200', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE flow_definition IS '流程定义表';
