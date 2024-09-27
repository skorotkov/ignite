/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.jdbc.thin;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import org.apache.ignite.internal.processors.odbc.SqlInputStreamWrapper;
import org.apache.ignite.internal.processors.odbc.SqlListenerUtils;
import org.apache.ignite.internal.processors.odbc.SqlStateCode;
import org.apache.ignite.internal.processors.odbc.jdbc.JdbcMetaParamsRequest;
import org.apache.ignite.internal.processors.odbc.jdbc.JdbcMetaParamsResult;
import org.apache.ignite.internal.processors.odbc.jdbc.JdbcQuery;
import org.apache.ignite.internal.processors.odbc.jdbc.JdbcStatementType;

import static java.sql.Types.BINARY;
import static org.apache.ignite.internal.processors.odbc.SqlInputStreamWrapper.MAX_ARRAY_SIZE;

/**
 * JDBC prepared statement implementation.
 */
public class JdbcThinPreparedStatement extends JdbcThinStatement implements PreparedStatement {
    /** SQL query. */
    private final String sql;

    /** Query arguments. */
    protected ArrayList<Object> args;

    /** Parameters metadata. */
    private JdbcThinParameterMetadata metaData;

    /**
     * Creates new prepared statement.
     *
     * @param conn Connection.
     * @param sql SQL query.
     * @param resHoldability Result set holdability.
     * @param schema Schema name.
     */
    JdbcThinPreparedStatement(JdbcThinConnection conn, String sql, int resHoldability, String schema) {
        super(conn, resHoldability, schema);

        this.sql = sql;
    }

    /** {@inheritDoc} */
    @Override public ResultSet executeQuery() throws SQLException {
        executeWithArguments(JdbcStatementType.SELECT_STATEMENT_TYPE);

        ResultSet rs = getResultSet();

        if (rs == null)
            throw new SQLException("The query isn't SELECT query: " + sql, SqlStateCode.PARSING_EXCEPTION);

        return rs;
    }

    /** {@inheritDoc} */
    @Override public ResultSet executeQuery(String sql) throws SQLException {
        throw new SQLException("The method 'executeQuery(String)' is called on PreparedStatement instance.",
            SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public int executeUpdate() throws SQLException {
        executeWithArguments(JdbcStatementType.UPDATE_STMT_TYPE);

        int res = getUpdateCount();

        if (res == -1)
            throw new SQLException("The query is not DML statement: " + sql, SqlStateCode.PARSING_EXCEPTION);

        return res;
    }

    /** {@inheritDoc} */
    @Override public int executeUpdate(String sql) throws SQLException {
        throw new SQLException("The method 'executeUpdate(String)' is called on PreparedStatement instance.",
            SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw new SQLException("The method 'executeUpdate(String, int)' is called on PreparedStatement instance.",
            SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public int executeUpdate(String sql, int columnIndexes[]) throws SQLException {
        throw new SQLException("The method 'executeUpdate(String, int[])' is called on PreparedStatement instance.",
            SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public int executeUpdate(String sql, String columnNames[]) throws SQLException {
        throw new SQLException("The method 'executeUpdate(String, String[])' is called on PreparedStatement " +
            "instance.", SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public void setNull(int paramIdx, int sqlType) throws SQLException {
        setArgument(paramIdx, null);
    }

    /** {@inheritDoc} */
    @Override public void setBoolean(int paramIdx, boolean x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setByte(int paramIdx, byte x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setShort(int paramIdx, short x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setInt(int paramIdx, int x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setLong(int paramIdx, long x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setFloat(int paramIdx, float x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setDouble(int paramIdx, double x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setBigDecimal(int paramIdx, BigDecimal x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setString(int paramIdx, String x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setBytes(int paramIdx, byte[] x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setDate(int paramIdx, Date x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setTime(int paramIdx, Time x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setTimestamp(int paramIdx, Timestamp x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setAsciiStream(int paramIdx, InputStream x, int length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setUnicodeStream(int paramIdx, InputStream x, int length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setBinaryStream(int paramIdx, InputStream x, int length) throws SQLException {
        setBinaryStream(paramIdx, x, (long)length);
    }

    /** {@inheritDoc} */
    @Override public void clearParameters() throws SQLException {
        ensureNotClosed();

        args = null;
    }

    /** {@inheritDoc} */
    @Override public void setObject(int paramIdx, Object x, int targetSqlType) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setObject(int paramIdx, Object x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public boolean execute() throws SQLException {
        executeWithArguments(JdbcStatementType.ANY_STATEMENT_TYPE);

        return resultSets.get(0).isQuery();
    }

    /**
     * Execute query with arguments and nullify them afterwards.
     *
     * @param stmtType Expected statement type.
     * @throws SQLException If failed.
     */
    private void executeWithArguments(JdbcStatementType stmtType) throws SQLException {
        execute0(stmtType, sql, args);
    }

    /** {@inheritDoc} */
    @Override public boolean execute(String sql) throws SQLException {
        throw new SQLException("The method 'execute(String)' is called on PreparedStatement instance.",
            SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public void addBatch() throws SQLException {
        ensureNotClosed();

        checkStatementEligibleForBatching(sql);

        checkStatementBatchEmpty();

        batchSize++;

        if (conn.isStream())
            conn.addBatch(sql, args);
        else {
            if (batch == null) {
                batch = new ArrayList<>();

                batch.add(new JdbcQuery(sql, args.toArray(new Object[args.size()])));
            }
            else
                batch.add(new JdbcQuery(null, args.toArray(new Object[args.size()])));
        }

        args = null;
    }

    /** {@inheritDoc} */
    @Override public void addBatch(String sql) throws SQLException {
        throw new SQLException("The method 'addBatch(String)' is called on PreparedStatement instance.",
            SqlStateCode.UNSUPPORTED_OPERATION);
    }

    /** {@inheritDoc} */
    @Override public void setCharacterStream(int paramIdx, Reader x, int length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setRef(int paramIdx, Ref x) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setBlob(int paramIdx, Blob x) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setClob(int paramIdx, Clob x) throws SQLException {
        setString(paramIdx, x.getSubString(1, (int)x.length()));
    }

    /** {@inheritDoc} */
    @Override public void setArray(int paramIdx, Array x) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public ResultSetMetaData getMetaData() throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Meta data for prepared statement is not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setDate(int paramIdx, Date x, Calendar cal) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setTime(int paramIdx, Time x, Calendar cal) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setTimestamp(int paramIdx, Timestamp x, Calendar cal) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setNull(int paramIdx, int sqlType, String typeName) throws SQLException {
        if (!JdbcThinUtils.isPlainJdbcType(sqlType)) {
            throw new SQLFeatureNotSupportedException("The SQL type is unsupported. [type=" + sqlType + "typeName="
                + typeName + ']');
        }

        setNull(paramIdx, sqlType);
    }

    /** {@inheritDoc} */
    @Override public void setURL(int paramIdx, URL x) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Parameter type is unsupported. [cls=" + URL.class + ']');
    }

    /** {@inheritDoc} */
    @Override public ParameterMetaData getParameterMetaData() throws SQLException {
        ensureNotClosed();

        if (metaData != null)
            return metaData;

        JdbcMetaParamsResult res = conn.sendRequest(new JdbcMetaParamsRequest(conn.getSchema(), sql)).
            response();

        metaData = new JdbcThinParameterMetadata(res.meta());

        return metaData;
    }

    /** {@inheritDoc} */
    @Override public void setRowId(int paramIdx, RowId x) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setNString(int paramIdx, String val) throws SQLException {
        ensureNotClosed();

        setString(paramIdx, val);
    }

    /** {@inheritDoc} */
    @Override public void setNCharacterStream(int paramIdx, Reader val, long length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setNClob(int paramIdx, NClob val) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setClob(int paramIdx, Reader reader, long length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setBlob(int paramIdx, InputStream inputStream, long length) throws SQLException {
        setBinaryStream(paramIdx, inputStream, length);
    }

    /** {@inheritDoc} */
    @Override public void setNClob(int paramIdx, Reader reader, long length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setSQLXML(int paramIdx, SQLXML xmlObj) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setObject(int paramIdx, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setArgument(paramIdx, x);
    }

    /** {@inheritDoc} */
    @Override public void setAsciiStream(int paramIdx, InputStream x, long length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setBinaryStream(int paramIdx, InputStream x, long length) throws SQLException {
        ensureNotClosed();

        if (length < 0)
            throw new SQLException("Invalid argument. Length should be greater than 0.");

        if (length > MAX_ARRAY_SIZE)
            throw new SQLFeatureNotSupportedException("Invalid argument. InputStreams with length > " + MAX_ARRAY_SIZE +
                    " are not supported.");

        if (x == null)
            setNull(paramIdx, BINARY);
        else
            setArgument(paramIdx, SqlInputStreamWrapper.withKnownLength(x, (int)length));
    }

    /** {@inheritDoc} */
    @Override public void setCharacterStream(int paramIdx, Reader x, long length) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setAsciiStream(int paramIdx, InputStream x) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setBinaryStream(int paramIdx, InputStream x) throws SQLException {
        ensureNotClosed();

        if (x == null)
            setNull(paramIdx, BINARY);
        else
            setArgument(paramIdx, SqlInputStreamWrapper.withUnknownLength(x, conn.connectionProperties().getMaxInMemoryLobSize()));
    }

    /** {@inheritDoc} */
    @Override public void setCharacterStream(int paramIdx, Reader x) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("Streams are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setNCharacterStream(int paramIdx, Reader val) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setClob(int paramIdx, Reader reader) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public void setBlob(int paramIdx, InputStream inputStream) throws SQLException {
        setBinaryStream(paramIdx, inputStream);
    }

    /** {@inheritDoc} */
    @Override public void setNClob(int paramIdx, Reader reader) throws SQLException {
        ensureNotClosed();

        throw new SQLFeatureNotSupportedException("SQL-specific types are not supported.");
    }

    /** {@inheritDoc} */
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (!isWrapperFor(iface))
            throw new SQLException("Prepared statement is not a wrapper for " + iface.getName());

        return (T)this;
    }

    /** {@inheritDoc} */
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface != null && iface.isAssignableFrom(JdbcThinPreparedStatement.class);
    }

    /** {@inheritDoc} */
    @Override public void closeImpl() throws SQLException {
        if (args != null) {
            try {
                for (Object arg : args) {
                    if (arg instanceof AutoCloseable)
                        ((AutoCloseable)arg).close();
                }
            }
            catch (Exception e) {
                throw new SQLException(e);
            }
        }
    }

    /**
     * Sets query argument value.
     *
     * @param paramIdx Index.
     * @param val Value.
     * @throws SQLException If index is invalid.
     */
    private void setArgument(int paramIdx, Object val) throws SQLException {
        ensureNotClosed();

        if (val != null && !SqlListenerUtils.isPlainType(val.getClass())
                && !(val instanceof SqlInputStreamWrapper) && !(val instanceof Blob))
            ensureCustomObjectsSupported();

        if (paramIdx < 1)
            throw new SQLException("Parameter index is invalid: " + paramIdx);

        if (args == null)
            args = new ArrayList<>(paramIdx);

        while (args.size() < paramIdx)
            args.add(null);

        args.set(paramIdx - 1, val);
    }

    /**
     * Ensures that statement support custom objects.
     *
     * @throws SQLException If statement don't support custom objects.
     */
    private void ensureCustomObjectsSupported() throws SQLException {
        if (!conn.isCustomObjectSupported())
            throw new SQLException("Custom objects are not supported");
    }
}
