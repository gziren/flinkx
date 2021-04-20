/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.connector.jdbc.table;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.utils.TableSchemaUtils;
import org.apache.flink.util.Preconditions;

import com.dtstack.flinkx.connector.jdbc.JdbcDialect;
import com.dtstack.flinkx.connector.jdbc.conf.JdbcConf;
import com.dtstack.flinkx.connector.jdbc.conf.JdbcLookupConf;
import com.dtstack.flinkx.connector.jdbc.conf.SinkConnectionConf;
import com.dtstack.flinkx.connector.jdbc.sink.JdbcDynamicTableSink;
import com.dtstack.flinkx.connector.jdbc.source.JdbcDynamicTableSource;
import com.dtstack.flinkx.lookup.conf.LookupConf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.dtstack.flinkx.connector.jdbc.constants.JdbcCommonConstants.PASSWORD;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcCommonConstants.SCHEMA;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcCommonConstants.TABLE_NAME;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcCommonConstants.URL;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcCommonConstants.USERNAME;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcLookUpConstants.LOOKUP_ASYNCPOOLSIZE;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSinkConstants.SINK_ALLREPLACE;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSinkConstants.SINK_BUFFER_FLUSH_INTERVAL;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSinkConstants.SINK_BUFFER_FLUSH_MAX_ROWS;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSinkConstants.SINK_MAX_RETRIES;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSinkConstants.SINK_PARALLELISM;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSourceConstants.SCAN_AUTO_COMMIT;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSourceConstants.SCAN_FETCH_SIZE;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSourceConstants.SCAN_PARTITION_COLUMN;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSourceConstants.SCAN_PARTITION_LOWER_BOUND;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSourceConstants.SCAN_PARTITION_NUM;
import static com.dtstack.flinkx.connector.jdbc.constants.JdbcSourceConstants.SCAN_PARTITION_UPPER_BOUND;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_ASYNCTIMEOUT;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_CACHE_MAX_ROWS;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_CACHE_PERIOD;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_CACHE_TTL;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_CACHE_TYPE;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_ERRORLIMIT;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_FETCH_SIZE;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_MAX_RETRIES;
import static com.dtstack.flinkx.lookup.constants.LookUpConstants.LOOKUP_PARALLELISM;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * @author chuixue
 * @create 2021-04-10 12:54
 * @description
 **/
abstract public class JdbcDynamicTableFactory implements DynamicTableSourceFactory, DynamicTableSinkFactory {

    @Override
    public DynamicTableSource createDynamicTableSource(Context context) {
        final FactoryUtil.TableFactoryHelper helper =
                FactoryUtil.createTableFactoryHelper(this, context);
        // 1.所有的requiredOptions和optionalOptions参数
        final ReadableConfig config = helper.getOptions();

        // 2.参数校验
        helper.validate();
        validateConfigOptions(config);

        // 3.封装参数
        TableSchema physicalSchema =
                TableSchemaUtils.getPhysicalSchema(context.getCatalogTable().getSchema());
        JdbcDialect jdbcDialect = getDialect();

        return new JdbcDynamicTableSource(
                getConnectionConf(helper.getOptions(), physicalSchema),
                getJdbcLookupConf(
                        helper.getOptions(),
                        context.getObjectIdentifier().getObjectName()),
                physicalSchema,
                jdbcDialect
        );
    }

    @Override
    public DynamicTableSink createDynamicTableSink(Context context) {
        final FactoryUtil.TableFactoryHelper helper =
                FactoryUtil.createTableFactoryHelper(this, context);
        // 1.所有的requiredOptions和optionalOptions参数
        final ReadableConfig config = helper.getOptions();

        // 2.参数校验
        helper.validate();
        validateConfigOptions(config);
        JdbcDialect jdbcDialect = getDialect();

        // 3.封装参数
        TableSchema physicalSchema =
                TableSchemaUtils.getPhysicalSchema(context.getCatalogTable().getSchema());

        return new JdbcDynamicTableSink(
                getConnectionConf(helper.getOptions(), physicalSchema),
                jdbcDialect,
                physicalSchema);
    }

    protected JdbcConf getConnectionConf(ReadableConfig readableConfig, TableSchema schema) {
        JdbcConf jdbcConf = new JdbcConf();
        SinkConnectionConf conf = new SinkConnectionConf();

        conf.setJdbcUrl(readableConfig.get(URL));
        List<String> tableNames = new ArrayList<>();
        tableNames.add(readableConfig.get(TABLE_NAME));
        conf.setTable(tableNames);
        conf.setSchema(readableConfig.get(SCHEMA));
        conf.setAllReplace(readableConfig.get(SINK_ALLREPLACE));

        readableConfig.getOptional(USERNAME).ifPresent(conf::setUsername);
        readableConfig.getOptional(PASSWORD).ifPresent(conf::setPassword);
        jdbcConf.setConnection(Collections.singletonList(conf));
        jdbcConf.setUsername(conf.getUsername());
        jdbcConf.setPassword(conf.getPassword());

        jdbcConf.setAllReplace(conf.getAllReplace());
        jdbcConf.setBatchSize(readableConfig.get(SINK_BUFFER_FLUSH_MAX_ROWS));
        jdbcConf.setFlushIntervalMills(readableConfig.get(SINK_BUFFER_FLUSH_INTERVAL));
        jdbcConf.setParallelism(readableConfig.get(SINK_PARALLELISM));

        List<String> keyFields =
                schema.getPrimaryKey()
                        .map(pk -> pk.getColumns())
                        .orElse(null);
        jdbcConf.setUpdateKey(keyFields);

        return jdbcConf;
    }

    protected LookupConf getJdbcLookupConf(ReadableConfig readableConfig, String tableName) {
        return JdbcLookupConf
                .build()
                .setAsyncPoolSize(readableConfig.get(LOOKUP_ASYNCPOOLSIZE))
                .setTableName(tableName)
                .setPeriod(readableConfig.get(LOOKUP_CACHE_PERIOD))
                .setCacheSize(readableConfig.get(LOOKUP_CACHE_MAX_ROWS))
                .setCacheTtl(readableConfig.get(LOOKUP_CACHE_TTL))
                .setCache(readableConfig.get(LOOKUP_CACHE_TYPE))
                .setMaxRetryTimes(readableConfig.get(LOOKUP_MAX_RETRIES))
                .setErrorLimit(readableConfig.get(LOOKUP_ERRORLIMIT))
                .setFetchSize(readableConfig.get(LOOKUP_FETCH_SIZE))
                .setAsyncTimeout(readableConfig.get(LOOKUP_ASYNCTIMEOUT))
                .setParallelism(readableConfig.get(LOOKUP_PARALLELISM));
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        Set<ConfigOption<?>> requiredOptions = new HashSet<>();
        requiredOptions.add(URL);
        requiredOptions.add(TABLE_NAME);
        return requiredOptions;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> optionalOptions = new HashSet<>();
        optionalOptions.add(USERNAME);
        optionalOptions.add(PASSWORD);

        optionalOptions.add(SCAN_PARTITION_COLUMN);
        optionalOptions.add(SCAN_PARTITION_LOWER_BOUND);
        optionalOptions.add(SCAN_PARTITION_UPPER_BOUND);
        optionalOptions.add(SCAN_PARTITION_NUM);
        optionalOptions.add(SCAN_FETCH_SIZE);
        optionalOptions.add(SCAN_AUTO_COMMIT);

        optionalOptions.add(LOOKUP_CACHE_PERIOD);
        optionalOptions.add(LOOKUP_CACHE_MAX_ROWS);
        optionalOptions.add(LOOKUP_CACHE_TTL);
        optionalOptions.add(LOOKUP_CACHE_TYPE);
        optionalOptions.add(LOOKUP_MAX_RETRIES);
        optionalOptions.add(LOOKUP_ERRORLIMIT);
        optionalOptions.add(LOOKUP_FETCH_SIZE);
        optionalOptions.add(LOOKUP_ASYNCTIMEOUT);
        optionalOptions.add(LOOKUP_PARALLELISM);

        optionalOptions.add(SINK_BUFFER_FLUSH_MAX_ROWS);
        optionalOptions.add(SINK_BUFFER_FLUSH_INTERVAL);
        optionalOptions.add(SINK_MAX_RETRIES);
        optionalOptions.add(SINK_ALLREPLACE);
        optionalOptions.add(SINK_PARALLELISM);
        return optionalOptions;
    }

    protected void validateConfigOptions(ReadableConfig config) {
        String jdbcUrl = config.get(URL);
        final Optional<JdbcDialect> dialect = Optional.of(getDialect());
        checkState(dialect.isPresent(), "Cannot handle such jdbc url: " + jdbcUrl);

        checkAllOrNone(config, new ConfigOption[]{USERNAME, PASSWORD});

        checkAllOrNone(
                config,
                new ConfigOption[]{
                        SCAN_PARTITION_COLUMN,
                        SCAN_PARTITION_NUM,
                        SCAN_PARTITION_LOWER_BOUND,
                        SCAN_PARTITION_UPPER_BOUND
                });

        if (config.getOptional(SCAN_PARTITION_LOWER_BOUND).isPresent()
                && config.getOptional(SCAN_PARTITION_UPPER_BOUND).isPresent()) {
            long lowerBound = config.get(SCAN_PARTITION_LOWER_BOUND);
            long upperBound = config.get(SCAN_PARTITION_UPPER_BOUND);
            if (lowerBound > upperBound) {
                throw new IllegalArgumentException(
                        String.format(
                                "'%s'='%s' must not be larger than '%s'='%s'.",
                                SCAN_PARTITION_LOWER_BOUND.key(),
                                lowerBound,
                                SCAN_PARTITION_UPPER_BOUND.key(),
                                upperBound));
            }
        }

        checkAllOrNone(config, new ConfigOption[]{LOOKUP_CACHE_MAX_ROWS, LOOKUP_CACHE_TTL});

        if (config.get(LOOKUP_MAX_RETRIES) < 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "The value of '%s' option shouldn't be negative, but is %s.",
                            LOOKUP_MAX_RETRIES.key(), config.get(LOOKUP_MAX_RETRIES)));
        }

        if (config.get(SINK_MAX_RETRIES) < 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "The value of '%s' option shouldn't be negative, but is %s.",
                            SINK_MAX_RETRIES.key(), config.get(SINK_MAX_RETRIES)));
        }
    }

    private void checkAllOrNone(ReadableConfig config, ConfigOption<?>[] configOptions) {
        int presentCount = 0;
        for (ConfigOption configOption : configOptions) {
            if (config.getOptional(configOption).isPresent()) {
                presentCount++;
            }
        }
        String[] propertyNames =
                Arrays.stream(configOptions).map(ConfigOption::key).toArray(String[]::new);
        Preconditions.checkArgument(
                configOptions.length == presentCount || presentCount == 0,
                "Either all or none of the following options should be provided:\n"
                        + String.join("\n", propertyNames));
    }

    /**
     * 子类根据不同数据库定义不同标记
     *
     * @return
     */
    @Override
    public abstract String factoryIdentifier();

    /**
     * 不同数据库不同方言
     *
     * @return
     */
    protected abstract JdbcDialect getDialect();
}
