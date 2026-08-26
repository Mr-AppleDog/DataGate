-- =============================================================================
-- V3: DataGate M0-02 基座清理（docs/10 M0-02、docs/00 第 3.2 节）
--
-- 1. 删除演示表（test_demo / test_tree / test_leave）
-- 2. 删除演示菜单（测试菜单及子菜单、请假申请示例）及角色绑定
-- 3. 关闭多租户入口：删除租户管理菜单（后端已由 tenant.enable=false 关闭）
-- 4. 删除上游样例账号 test / test1（保留 admin 作为唯一引导账号，M1 强制改密+TOTP）
-- 5. 清除上游示例 OSS 配置中的示例密钥
-- 6. 显式确认禁止公开注册（sys.account.registerUser=false）
-- 7. 删除工作流示例分类与示例 SpEL（DataGate 审批流将另行定义）
-- =============================================================================

-- ---------- 1. 演示表 ----------
DROP TABLE IF EXISTS test_demo;
DROP TABLE IF EXISTS test_tree;
DROP TABLE IF EXISTS test_leave;

-- ---------- 2/3. 演示与租户菜单 ----------
-- 5=测试菜单, 1500-1511=测试单表/树表, 11638-11643=请假申请(演示), 11701=请假申请(工作流示例)
-- 6=租户管理, 121=租户管理, 122=租户套餐管理, 1606-1615=租户相关按钮
DELETE FROM sys_role_menu
WHERE menu_id IN (5, 6, 121, 122, 11701,
                  1500, 1501, 1502, 1503, 1504, 1505,
                  1506, 1507, 1508, 1509, 1510, 1511,
                  1606, 1607, 1608, 1609, 1610,
                  1611, 1612, 1613, 1614, 1615,
                  11638, 11639, 11640, 11641, 11642, 11643);

DELETE FROM sys_menu
WHERE menu_id IN (5, 6, 121, 122, 11701,
                  1500, 1501, 1502, 1503, 1504, 1505,
                  1506, 1507, 1508, 1509, 1510, 1511,
                  1606, 1607, 1608, 1609, 1610,
                  1611, 1612, 1613, 1614, 1615,
                  11638, 11639, 11640, 11641, 11642, 11643);

-- ---------- 4. 样例账号 ----------
DELETE FROM sys_user_role WHERE user_id IN (3, 4);
DELETE FROM sys_user_post WHERE user_id IN (3, 4);
DELETE FROM sys_user WHERE user_id IN (3, 4);

-- ---------- 5. 示例 OSS 配置（清除上游示例密钥并停用，DataGate 导出对象存储在 M5 统一配置） ----------
UPDATE sys_oss_config
SET access_key = '', secret_key = '', status = '1', endpoint = '', bucket_name = '', region = '',
    remark = 'M0-02 已清除上游示例配置，DataGate 对象存储由 M5 受控接入'
WHERE oss_config_id IN (1, 2, 3, 4, 5);

-- ---------- 6. 禁止公开注册（显式兜底，防止误开） ----------
UPDATE sys_config SET config_value = 'false', update_time = now()
WHERE config_key = 'sys.account.registerUser';

-- ---------- 7. 工作流示例数据 ----------
DELETE FROM flow_category WHERE category_id BETWEEN 100 AND 109;
DELETE FROM flow_spel WHERE id IN (1, 2);
