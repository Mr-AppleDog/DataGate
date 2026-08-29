package org.dromara.db.core.enums;

/**
 * 授权主体类型（docs/03 第 5.2 节）。
 * 岗位只用于功能角色分配，不作为数据资源授权主体。
 *
 * @author DataGate
 */
public enum SubjectType {

    USER,
    DEPT,
    GROUP,

    /**
     * 平台角色。仅显式写入资源授权表的角色可获得数据权限，角色名称本身不触发隐藏放行。
     */
    ROLE
}
