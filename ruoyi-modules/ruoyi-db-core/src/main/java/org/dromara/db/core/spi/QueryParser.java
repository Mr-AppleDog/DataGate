package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ParsedStatement;

import java.util.List;

/**
 * 方言解析器（docs/02 第 4.1 节）。
 *
 * <p>P0 使用 Alibaba Druid SQL Parser 的 Parser/AST/Visitor；
 * 禁止用正则/关键字替代 AST；解析失败必须失败关闭，
 * 不得交给数据库试运行；WallFilter 判定不得作为最终授权。</p>
 *
 * @author DataGate
 */
public interface QueryParser {

    /**
     * 解析语句并提取引用资源。解析失败或不支持的语法抛出异常（失败关闭）。
     *
     * @param statement 原始语句
     * @return 解析结果列表（多语句逐条返回）
     */
    List<ParsedStatement> parse(String statement);

    /**
     * 解析器版本（语料回归测试基线）
     */
    String parserVersion();
}
