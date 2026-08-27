package org.dromara.db.resource.support;

import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 资源层级解析单测（M2-02 接通：祖先继承链，docs/03 §5.1）。
 */
@Tag("unit")
class DbResourceHierarchyResolverTest {

    private static DbResource res(Long id, Long parentId, String status, String type) {
        DbResource r = new DbResource();
        r.setId(id);
        r.setParentId(parentId);
        r.setStatus(status);
        r.setResourceType(type);
        return r;
    }

    @Test
    void resolvesChainFromTableToDataSourceRoot() {
        // sys_menu(100) → data-gate DATABASE(200) → DATA_SOURCE(300) → null
        DbResourceMapper mapper = mock(DbResourceMapper.class);
        when(mapper.selectById(100L)).thenReturn(res(100L, 200L, "ACTIVE", "TABLE"));
        when(mapper.selectById(200L)).thenReturn(res(200L, 300L, "ACTIVE", "DATABASE"));
        when(mapper.selectById(300L)).thenReturn(res(300L, null, "ACTIVE", "DATA_SOURCE"));

        List<Long> chain = new DbResourceHierarchyResolver(mapper).resolveAncestors(100L);

        assertEquals(List.of(100L, 200L, 300L), chain, "自身在前 + 全部祖先到 DATA_SOURCE 根");
    }

    @Test
    void selfDisabledReturnsEmpty() {
        DbResourceMapper mapper = mock(DbResourceMapper.class);
        when(mapper.selectById(100L)).thenReturn(res(100L, 200L, "DISABLED", "TABLE"));
        List<Long> chain = new DbResourceHierarchyResolver(mapper).resolveAncestors(100L);
        assertTrue(chain.isEmpty(), "自身非 ACTIVE → 不可解析失败关闭");
    }

    @Test
    void ancestorDisabledStopsBefore() {
        // sys_menu(100,ACTIVE) → DATABASE(200,DISABLED) → ...
        DbResourceMapper mapper = mock(DbResourceMapper.class);
        when(mapper.selectById(100L)).thenReturn(res(100L, 200L, "ACTIVE", "TABLE"));
        when(mapper.selectById(200L)).thenReturn(res(200L, 300L, "DISABLED", "DATABASE"));
        List<Long> chain = new DbResourceHierarchyResolver(mapper).resolveAncestors(100L);
        assertEquals(List.of(100L), chain, "祖先 DISABLED 止于其下，不加入 DISABLED 及以上");
    }

    @Test
    void cycleGuardTruncates() {
        // 100→200→100 循环
        DbResourceMapper mapper = mock(DbResourceMapper.class);
        when(mapper.selectById(100L)).thenReturn(res(100L, 200L, "ACTIVE", "TABLE"));
        when(mapper.selectById(200L)).thenReturn(res(200L, 100L, "ACTIVE", "DATABASE"));
        List<Long> chain = new DbResourceHierarchyResolver(mapper).resolveAncestors(100L);
        assertEquals(List.of(100L, 200L), chain, "环检测截断");
    }

    @Test
    void nullResourceReturnsEmpty() {
        DbResourceMapper mapper = mock(DbResourceMapper.class);
        List<Long> chain = new DbResourceHierarchyResolver(mapper).resolveAncestors(null);
        assertTrue(chain.isEmpty());
    }

    @Test
    void notFoundSelfReturnsEmpty() {
        DbResourceMapper mapper = mock(DbResourceMapper.class);
        when(mapper.selectById(999L)).thenReturn(null);
        List<Long> chain = new DbResourceHierarchyResolver(mapper).resolveAncestors(999L);
        assertTrue(chain.isEmpty(), "自身不存在 → 失败关闭");
    }
}
