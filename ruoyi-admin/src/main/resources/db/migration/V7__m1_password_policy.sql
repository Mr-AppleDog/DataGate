-- =============================================================================
-- V7 M1-01 本地账号强化（IAM-002 首次登录强制改密 / IAM-003 密码策略）
--
-- dbg_user_security：用户安全状态（DataGate 自有表，不改上游 sys_user 结构）。
--   must_change_pwd=true 时登录被拒绝（IAM_PASSWORD_CHANGE_REQUIRED），
--   只能通过预认证改密端点完成首次改密后才可访问业务功能。
-- 失败关闭：迁移将存量全部用户标记为必须改密（含引导账号 admin）。
-- =============================================================================

create table if not exists dbg_user_security (
    user_id          bigint                   not null,
    must_change_pwd  boolean                  not null default false,
    pwd_changed_at   timestamptz,
    policy_version   integer                  not null default 1,
    create_time      timestamptz              not null default now(),
    update_time      timestamptz              not null default now(),
    constraint pk_dbg_user_security primary key (user_id)
);

comment on table  dbg_user_security is 'DataGate 用户安全状态（首次改密标记、密码策略版本），IAM-002/IAM-003';
comment on column dbg_user_security.user_id is 'sys_user.user_id';
comment on column dbg_user_security.must_change_pwd is '是否必须修改初始密码后才能登录';
comment on column dbg_user_security.pwd_changed_at is '最近一次密码修改时间（UTC）';
comment on column dbg_user_security.policy_version is '最近一次设密时适用的密码策略版本';

-- 存量用户一律标记为必须改密（含 admin 引导账号，VERSION-LOCK 收口项）
insert into dbg_user_security (user_id, must_change_pwd, policy_version)
select u.user_id, true, 1
from sys_user u
on conflict (user_id) do nothing;
