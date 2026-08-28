package org.dromara.dromara.ops;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 节点排空状态（docs/09 §11，M6-01b）。优雅停机时置 true，readiness 探针据此返回失败。
 *
 * @author DataGate
 */
@Component
public class DrainState {

    private final AtomicBoolean draining = new AtomicBoolean(false);

    public boolean isDraining() {
        return draining.get();
    }

    public void setDraining(boolean v) {
        draining.set(v);
    }
}
