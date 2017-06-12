/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.jdbc.core.statement;

import com.dangdang.ddframe.rdb.sharding.executor.type.batch.BatchPreparedStatementUnit;
import com.dangdang.ddframe.rdb.sharding.executor.type.batch.BatchPreparedStatementExecutor;
import com.dangdang.ddframe.rdb.sharding.executor.type.prepared.PreparedStatementExecutor;
import com.dangdang.ddframe.rdb.sharding.executor.type.prepared.PreparedStatementUnit;
import com.dangdang.ddframe.rdb.sharding.jdbc.adapter.AbstractPreparedStatementAdapter;
import com.dangdang.ddframe.rdb.sharding.jdbc.core.connection.ShardingConnection;
import com.dangdang.ddframe.rdb.sharding.merger.ResultSetFactory;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.GeneratedKey;
import com.dangdang.ddframe.rdb.sharding.routing.PreparedStatementRoutingEngine;
import com.dangdang.ddframe.rdb.sharding.routing.SQLExecutionUnit;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * 支持分片的预编译语句对象.
 * 
 * @author zhangliang
 * @author caohao
 */
public final class ShardingPreparedStatement extends AbstractPreparedStatementAdapter {
    
    private final PreparedStatementRoutingEngine routingEngine;
    
    private final List<BatchPreparedStatementUnit> batchStatementUnits = new LinkedList<>();
    
    private final List<List<Object>> parameterSets = new LinkedList<>();
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql) {
        this(shardingConnection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql, final int resultSetType, final int resultSetConcurrency) {
        this(shardingConnection, sql, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql, final int autoGeneratedKeys) {
        this(shardingConnection, sql);
        if (RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            markReturnGeneratedKeys();
        }
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        super(shardingConnection, resultSetType, resultSetConcurrency, resultSetHoldability);
        routingEngine = new PreparedStatementRoutingEngine(sql, shardingConnection.getShardingContext());
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        ResultSet result;
        try {
            Collection<PreparedStatementUnit> preparedStatementUnits = routeSingle();
            result = ResultSetFactory.getResultSet(new PreparedStatementExecutor(getShardingConnection().getShardingContext().getExecutorEngine(), 
                    getRouteResult().getSqlStatement().getType(), preparedStatementUnits, getParameters()).executeQuery(), getRouteResult().getSqlStatement());
        } finally {
            clearBatch();
        }
        setCurrentResultSet(result);
        return result;
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        try {
            Collection<PreparedStatementUnit> preparedStatementUnits = routeSingle();
            return new PreparedStatementExecutor(
                    getShardingConnection().getShardingContext().getExecutorEngine(), getRouteResult().getSqlStatement().getType(), preparedStatementUnits, getParameters()).executeUpdate();
        } finally {
            clearBatch();
        }
    }
    
    @Override
    public boolean execute() throws SQLException {
        try {
            Collection<PreparedStatementUnit> preparedStatementUnits = routeSingle();
            return new PreparedStatementExecutor(
                    getShardingConnection().getShardingContext().getExecutorEngine(), getRouteResult().getSqlStatement().getType(), preparedStatementUnits, getParameters()).execute();
        } finally {
            clearBatch();
        }
    }
    
    private Collection<PreparedStatementUnit> routeSingle() throws SQLException {
        Collection<PreparedStatementUnit> result = new LinkedList<>();
        setRouteResult(routingEngine.route(getParameters()));
        for (SQLExecutionUnit each : getRouteResult().getExecutionUnits()) {
            PreparedStatement preparedStatement = generatePreparedStatement(each);
            getRoutedStatements().add(preparedStatement);
            replaySetParameter(preparedStatement);
            result.add(new PreparedStatementUnit(each, preparedStatement));
        }
        return result;
    }
    
    private PreparedStatement generatePreparedStatement(final SQLExecutionUnit sqlExecutionUnit) throws SQLException {
        Optional<GeneratedKey> generatedKey = getGeneratedKey();
        Connection connection = getShardingConnection().getConnection(sqlExecutionUnit.getDataSource(), getRouteResult().getSqlStatement().getType());
        if (isReturnGeneratedKeys() && generatedKey.isPresent()) {
            return connection.prepareStatement(sqlExecutionUnit.getSql(), RETURN_GENERATED_KEYS);
        }
        return connection.prepareStatement(sqlExecutionUnit.getSql(), getResultSetType(), getResultSetConcurrency(), getResultSetHoldability());
    }
    
    @Override
    public void clearBatch() throws SQLException {
        setCurrentResultSet(null);
        clearParameters();
        batchStatementUnits.clear();
        parameterSets.clear();
    }
    
    @Override
    public void addBatch() throws SQLException {
        try {
            for (BatchPreparedStatementUnit each : routeBatch()) {
                each.getStatement().addBatch();
                each.mapAddBatchCount(parameterSets.size());
            }
            parameterSets.add(getParameters());
        } finally {
            setCurrentResultSet(null);
            clearParameters();
        }
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        try {
            return new BatchPreparedStatementExecutor(getShardingConnection().getShardingContext().getExecutorEngine(), 
                    getRouteResult().getSqlStatement().getType(), batchStatementUnits, parameterSets).executeBatch();
        } finally {
            clearBatch();
        }
    }
    
    private List<BatchPreparedStatementUnit> routeBatch() throws SQLException {
        List<BatchPreparedStatementUnit> result = new ArrayList<>();
        setRouteResult(routingEngine.route(getParameters()));
        for (SQLExecutionUnit each : getRouteResult().getExecutionUnits()) {
            BatchPreparedStatementUnit batchStatementUnit = getPreparedBatchStatement(each);
            replaySetParameter(batchStatementUnit.getStatement());
            result.add(batchStatementUnit);
        }
        return result;
    }
    
    private BatchPreparedStatementUnit getPreparedBatchStatement(final SQLExecutionUnit sqlExecutionUnit) throws SQLException {
        Optional<BatchPreparedStatementUnit> preparedBatchStatement = Iterators.tryFind(batchStatementUnits.iterator(), new Predicate<BatchPreparedStatementUnit>() {
            
            @Override
            public boolean apply(final BatchPreparedStatementUnit input) {
                return Objects.equals(input.getSqlExecutionUnit(), sqlExecutionUnit);
            }
        });
        if (preparedBatchStatement.isPresent()) {
            return preparedBatchStatement.get();
        }
        BatchPreparedStatementUnit result = new BatchPreparedStatementUnit(sqlExecutionUnit, generatePreparedStatement(sqlExecutionUnit));
        batchStatementUnits.add(result);
        return result;
    }
}
