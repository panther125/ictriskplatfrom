package com.riskstrategy.engine;

import com.riskstrategy.dto.ResolvedInputDTO;
import jakarta.annotation.PostConstruct;
import org.apache.calcite.DataContext;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** 衍生特征 SQL 引擎：@@/:param 替换为 SQL 字面量 → Calcite 内存执行（t_input 单行输入表）。 */
@Component
public class SqlFeatureEngine {

    static {
        // Calcite 字符串字面量默认按 ISO-8859-1 编码，中文会报 "Failed to encode"；
        // CalciteSystemProperty 只在类首次加载时读一次，必须抢在它加载前设置
        System.setProperty("calcite.default.charset", "UTF-8");
        System.setProperty("calcite.default.nationalcharset", "UTF-8");
    }

    public EngineResult execute(String expr, List<ResolvedInputDTO> inputs, Map<String, Object> testValues,
                                Map<String, Object> refValues, long timeoutMs) {
        long start = System.nanoTime();
        EngineResult r = new EngineResult();
        r.setEngine("SQL");

        String substituted = ExprLiterals.replace(expr, ExprLiterals.Lang.SQL, refValues, testValues).trim();
        while (substituted.endsWith(";")) substituted = substituted.substring(0, substituted.length() - 1).trim();
        if (substituted.isEmpty()) {
            r.setError("衍生特征表达式为空，请先编写 SQL");
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }
        if (substituted.startsWith("{")) {
            r.setError("当前表达式看起来是算子树 JSON，请把加工方式切换为 OP");
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }
        // Calcite 把 RESULT 列为保留字；平台示例建议写 `AS result`，这里改写为 `AS "result"`（列名不变）
        String sql = substituted.replaceAll("(?i)\\bAS\\s+result\\b(?!\\s*\")", "AS \"result\"");
        r.setExecutedExpr(sql);

        try {
            SqlParser.create(sql).parseQuery();
        } catch (Exception e) {
            r.setError("SQL 语法错误：" + EngineValues.firstLine(e.getMessage()));
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }

        // 组单行输入表 t_input：列 = 全部登记入参，值 = 本次测试值
        List<InputCol> cols = new ArrayList<>();
        for (ResolvedInputDTO iv : inputs) {
            SqlTypeName type = sqlTypeOf(iv.getType());
            Object raw = testValues == null ? null : testValues.get(iv.getName());
            cols.add(new InputCol(iv.getName(), type, parseValue(raw, type)));
        }
        if (cols.isEmpty()) {
            // 没有任何入参时也保留一行占位，SQL 里的 FROM t_input 依然可用
            cols.add(new InputCol("t_input_dummy", SqlTypeName.VARCHAR, null));
        }
        Object[] row = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) row[i] = cols.get(i).value();

        try {
            String finalSql = sql;
            ExecGuard.call(timeoutMs, () -> {
                runQuery(finalSql, cols, row, r);
                return null;
            });
        } catch (TimeoutException e) {
            r.setError("SQL 执行超时（超过 " + timeoutMs + " ms），已中断");
        } catch (Exception e) {
            String hint = inputs.isEmpty()
                    ? "（该特征没有登记入参，t_input 只有一行占位）"
                    : "（t_input 可用输入列：" + inputs.stream().map(ResolvedInputDTO::getName)
                        .collect(Collectors.joining(", ")) + "）";
            r.setError("SQL 执行失败：" + EngineValues.firstLine(sqlErrorLine(e)) + hint);
        }
        r.setElapsedMs(EngineValues.msSince(start));
        return r;
    }

    /** 取最贴近根因的报错行：Calcite 顶层 SQLException 只说「Error while preparing statement」，真正原因在 cause 链里 */
    private String sqlErrorLine(Exception e) {
        String msg = null;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CalciteContextException && t.getMessage() != null && !t.getMessage().isBlank()) {
                msg = t.getMessage();
            } else if (msg == null && t.getMessage() != null && !t.getMessage().isBlank()) {
                msg = t.getMessage();
            }
        }
        return msg == null ? String.valueOf(e) : msg;
    }

    private void runQuery(String sql, List<InputCol> cols, Object[] row, EngineResult r) throws SQLException {
        Properties props = new Properties();
        props.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
        props.setProperty(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), "UNCHANGED");
        props.setProperty(CalciteConnectionProperty.QUOTED_CASING.camelName(), "UNCHANGED");
        // 启用 MySQL 函数库（CONCAT / IFNULL / DATE_FORMAT 等），贴近用户写 SQL 的直觉
        props.setProperty(CalciteConnectionProperty.FUN.camelName(), "mysql");
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", props)) {
            CalciteConnection calcite = conn.unwrap(CalciteConnection.class);
            SchemaPlus root = calcite.getRootSchema();
            root.add("t_input", new InputTable(cols, row));

            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                if (n != 1) {
                    throw new SQLException("表达式必须只返回一列（当前返回 " + n + " 列），列别名建议写 AS result");
                }
                String label = md.getColumnLabel(1);
                if (!"result".equalsIgnoreCase(label)) {
                    r.getWarnings().add("建议把输出列别名写为 AS result（当前为「" + label + "」）");
                }
                if (rs.next()) {
                    r.setValue(normalize(rs.getObject(1)));
                    r.setValueType(mapSqlType(md.getColumnType(1)));
                    if (rs.next()) r.getWarnings().add("查询返回多行，只取第一行");
                } else {
                    r.setValue(null);
                    r.setValueType("STRING");
                    r.getWarnings().add("查询未返回任何行，衍生输出按 NULL 处理");
                }
                r.setSuccess(true);
            }
        }
    }

    private record InputCol(String name, SqlTypeName type, Object value) {}

    /** 启动预热：Calcite 首次 prepare/execute 要生成并加载大量类（约半秒），先跑一次免得用户首次试跑撞超时 */
    @PostConstruct
    void warmUp() {
        Thread t = new Thread(() -> {
            try {
                EngineResult r = new EngineResult();
                List<InputCol> cols = List.of(new InputCol("warmup_col", SqlTypeName.VARCHAR, "x"));
                runQuery("SELECT CONCAT('w', warmup_col) AS \"result\" FROM t_input", cols, new Object[]{"x"}, r);
            } catch (Throwable ignore) {
                // 预热失败不影响真实执行
            }
        }, "calcite-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** Calcite 内的单行输入表：列类型跟随特征登记入参，值即本次测试值 */
    private static final class InputTable extends AbstractTable implements ScannableTable {
        private final List<InputCol> cols;
        private final Object[] row;

        InputTable(List<InputCol> cols, Object[] row) {
            this.cols = cols;
            this.row = row;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            RelDataTypeFactory.Builder b = typeFactory.builder();
            for (InputCol c : cols) {
                RelDataType t = switch (c.type()) {
                    case INTEGER -> typeFactory.createSqlType(SqlTypeName.INTEGER);
                    case BIGINT -> typeFactory.createSqlType(SqlTypeName.BIGINT);
                    case DECIMAL -> typeFactory.createSqlType(SqlTypeName.DECIMAL, 20, 6);
                    case BOOLEAN -> typeFactory.createSqlType(SqlTypeName.BOOLEAN);
                    case TIMESTAMP -> typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
                    default -> typeFactory.createSqlType(SqlTypeName.VARCHAR, 65536);
                };
                b.add(c.name(), t);
            }
            return b.build();
        }

        @Override
        public Enumerable<Object[]> scan(DataContext root) {
            return Linq4j.asEnumerable(Collections.singletonList(row));
        }
    }

    private SqlTypeName sqlTypeOf(String t) {
        String s = t == null ? "" : t.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "INTEGER", "INT" -> SqlTypeName.INTEGER;
            case "LONG", "BIGINT" -> SqlTypeName.BIGINT;
            case "DECIMAL", "NUMBER", "DOUBLE", "FLOAT" -> SqlTypeName.DECIMAL;
            case "BOOLEAN", "BOOL" -> SqlTypeName.BOOLEAN;
            case "DATETIME", "TIMESTAMP", "DATE" -> SqlTypeName.TIMESTAMP;
            default -> SqlTypeName.VARCHAR;
        };
    }

    private Object parseValue(Object raw, SqlTypeName type) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return switch (type) {
                case INTEGER -> s.contains(".") ? new BigDecimal(s).intValue() : Integer.valueOf(s);
                case BIGINT -> s.contains(".") ? new BigDecimal(s).longValue() : Long.valueOf(s);
                case DECIMAL -> new BigDecimal(s);
                case BOOLEAN -> s.equals("1") || s.equalsIgnoreCase("true");
                case TIMESTAMP -> toTimestamp(s);
                default -> String.valueOf(raw);
            };
        } catch (Exception e) {
            return null;
        }
    }

    private java.sql.Timestamp toTimestamp(String s) {
        try { return java.sql.Timestamp.valueOf(s.replace('T', ' ').trim()); } catch (Exception ignore) { /* 尝试下一种 */ }
        try { return java.sql.Timestamp.valueOf(java.time.LocalDate.parse(s).atStartOfDay()); } catch (Exception ignore) { /* 放弃解析 */ }
        return null;
    }

    private Object normalize(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString().replace('T', ' ');
        if (v instanceof java.sql.Date || v instanceof java.sql.Time) return v.toString();
        return v;
    }

    private String mapSqlType(int jdbcType) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.TINYINT, Types.SMALLINT -> "INTEGER";
            case Types.BIGINT -> "LONG";
            case Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT, Types.REAL -> "DECIMAL";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TIMESTAMP, Types.DATE, Types.TIME -> "DATETIME";
            default -> "STRING";
        };
    }
}
