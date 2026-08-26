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
    GROUP
}
