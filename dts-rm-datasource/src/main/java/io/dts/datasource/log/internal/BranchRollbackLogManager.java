package io.dts.datasource.log.internal;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import io.dts.common.context.DtsXID;
import io.dts.common.exception.DtsException;
import io.dts.common.protocol.ResultCode;
import io.dts.common.util.BlobUtil;
import io.dts.datasource.DataSourceHolder;
import io.dts.datasource.log.DtsLogManager;
import io.dts.datasource.log.LogManagerHelper;
import io.dts.datasource.log.internal.undo.DtsUndo;
import io.dts.datasource.sql.model.LogModel;
import io.dts.datasource.sql.model.UndoLogType;
import io.dts.parser.struct.RollbackInfor;
import io.dts.parser.struct.TxcField;
import io.dts.parser.struct.TxcLine;
import io.dts.parser.struct.TxcRuntimeContext;
import io.dts.parser.struct.TxcTable;
import io.dts.parser.struct.TxcTableMeta;
import io.dts.parser.vistor.DtsTableMetaTools;

public class BranchRollbackLogManager extends DtsLogManager {
    private static Logger logger = LoggerFactory.getLogger(BranchRollbackLogManager.class);

    private TxcRuntimeContext getTxcRuntimeContexts(final long gid, final JdbcTemplate template) {
        String sql =
            String.format("select * from %s where status = 0 && " + "id = %d order by id desc", txcLogTableName, gid);
        List<TxcRuntimeContext> undos = LogManagerHelper.querySql(template, new RowMapper<TxcRuntimeContext>() {
            @Override
            public TxcRuntimeContext mapRow(ResultSet rs, int rowNum) throws SQLException {
                Blob blob = rs.getBlob("rollback_info");
                String str = BlobUtil.blob2string(blob);
                TxcRuntimeContext undoLogInfor = TxcRuntimeContext.decode(str);
                return undoLogInfor;
            }
        }, sql);
        if (undos == null) {
            return null;
        }
        if (undos.size() == 0) {
            return null;
        }
        if (undos.size() > 1) {
            throw new DtsException("check txc_undo_log, trx info duplicate");
        }
        return undos.get(0);
    }

    @Override
    public void branchRollback(LogModel context) throws SQLException {
        DataSource datasource = DataSourceHolder.getDataSource(context.getDbname());
        DataSourceTransactionManager tm = new DataSourceTransactionManager(datasource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(tm);
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                try {
                    JdbcTemplate template = new JdbcTemplate(datasource);
                    // 查询事务日志
                    long gid = DtsXID.getGlobalXID(context.getXid(), context.getBranchId());
                    TxcRuntimeContext undolog = getTxcRuntimeContexts(gid, template);
                    if (undolog == null) {
                        return;
                    }
                    for (RollbackInfor info : undolog.getInfor()) {
                        // 设置表meta
                        TxcTable o = info.getOriginalValue();
                        TxcTable p = info.getPresentValue();
                        String tablename = o.getTableName() == null ? p.getTableName() : o.getTableName();
                        TxcTableMeta tablemeta = null;
                        try {
                            tablemeta = DtsTableMetaTools.getTableMeta(tablename);
                        } catch (Exception e) {
                            ; // 吞掉
                        }
                        if (tablemeta == null) {
                            DataSource datasource = null;
                            Connection conn = null;
                            try {
                                datasource = template.getDataSource();
                                conn = DataSourceUtils.getConnection(datasource);
                                tablemeta = DtsTableMetaTools.getTableMeta(conn, tablename);
                            } finally {
                                if (conn != null) {
                                    DataSourceUtils.releaseConnection(conn, datasource);
                                }
                            }
                        }
                        o.setTableMeta(tablemeta);
                        p.setTableMeta(tablemeta);
                    }
                    logger.info(String.format("[logid:%d:xid:%s:branch:%d]", undolog.getId(), undolog.getXid(),
                        undolog.getBranchId()));
                    for (int i = undolog.getInfor().size(); i > 0; i--) {
                        RollbackInfor info = undolog.getInfor().get(i - 1);
                        // 检查脏写
                        checkDirtyRead(template, info);
                        List<String> rollbackSqls = DtsUndo.createDtsundo(info).buildRollbackSql();
                        logger.info("the rollback sql is " + rollbackSqls);
                        if (!CollectionUtils.isEmpty(rollbackSqls)) {
                            String[] rollbackSqlArray = rollbackSqls.toArray(new String[rollbackSqls.size()]);
                            template.batchUpdate(rollbackSqlArray);
                        }
                    }
                    // 删除undolog
                    String deleteSql = getDeleteUndoLogSql(Arrays.asList(context));
                    logger.info("delete undo log sql" + deleteSql);
                    template.execute(deleteSql);
                } catch (Exception ex) {
                    status.setRollbackOnly();
                    throw new DtsException(ex, "rollback error");
                }

            }
        });

    }

    private void checkDirtyRead(final JdbcTemplate template, final RollbackInfor info) {
        String selectSql = String.format("%s %s FOR UPDATE", info.getSelectSql(), info.getWhereCondition());
        StringBuilder retLog = new StringBuilder();

        long start = 0;
        if (logger.isDebugEnabled())
            start = System.currentTimeMillis();
        try {
            TxcTable p = info.getPresentValue();
            final String valueByLog = p.toString();

            TxcTable t = getDBTxcTable(template, selectSql, p);

            final String valueBySql = t.toString();

            retLog.append("--Log:[");
            retLog.append(valueByLog);
            retLog.append("]");

            retLog.append("--Db[");
            retLog.append(valueBySql);
            retLog.append("]");

            if (valueByLog.equals(valueBySql) == false) {
                throw new DtsException(ResultCode.ERROR.getValue(), "dirty read:" + retLog.toString());
            }
        } catch (Exception e) {
            throw new DtsException(e, "checkDirtyRead error:" + retLog.toString());
        } finally {
            if (logger.isDebugEnabled())
                logger.debug(selectSql + " cost " + (System.currentTimeMillis() - start) + " ms");
        }

    }

    private TxcTable getDBTxcTable(final JdbcTemplate template, final String selectSql, final TxcTable p) {
        TxcTable t = new TxcTable();
        t.setTableMeta(p.getTableMeta());
        template.query(selectSql, new RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws SQLException {
                java.sql.ResultSetMetaData rsmd = rs.getMetaData();
                int column = rsmd.getColumnCount();
                List<TxcField> fields = new ArrayList<TxcField>(column);
                for (int i = 1; i <= column; i++) {
                    TxcField field = new TxcField();
                    field.setFieldName(rsmd.getColumnName(i));
                    field.setFieldType(rsmd.getColumnType(i));
                    field.setFieldValue(rs.getObject(i));
                    fields.add(field);
                }

                TxcLine line = new TxcLine();
                line.setTableMeta(t.getTableMeta());
                line.setFields(fields);
                t.addLine(line);
            }
        });
        return t;
    }

    private String getDeleteUndoLogSql(final List<LogModel> contexts) {
        StringBuilder sb = new StringBuilder();
        boolean flag = false;
        for (LogModel c : contexts) {
            if (flag == true) {
                sb.append(",");
            } else {
                flag = true;
            }
            sb.append(c.getGlobalXid());
        }

        return String.format("delete from %s where id in (%s) and status = %d", txcLogTableName, sb.toString(),
            UndoLogType.COMMON_LOG.getValue());
    }

}
