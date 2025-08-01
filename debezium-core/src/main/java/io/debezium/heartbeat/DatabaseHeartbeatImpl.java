/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.heartbeat;

import java.sql.SQLException;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Field;
import io.debezium.function.BlockingConsumer;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.spi.OffsetContext;

/**
 *  Implementation of the heartbeat feature that allows for a DB query to be executed with every heartbeat.
 */
public class DatabaseHeartbeatImpl implements Heartbeat {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseHeartbeatImpl.class);

    public static final String HEARTBEAT_ACTION_QUERY_PROPERTY_NAME = "heartbeat.action.query";

    public static final Field HEARTBEAT_ACTION_QUERY = Field.create(HEARTBEAT_ACTION_QUERY_PROPERTY_NAME)
            .withDisplayName("An optional query to execute with every heartbeat")
            .withType(ConfigDef.Type.STRING)
            .withGroup(Field.createGroupEntry(Field.Group.ADVANCED_HEARTBEAT, 2))
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.LOW)
            .withDescription("The query executed with every heartbeat.");

    private final String heartBeatActionQuery;
    private final JdbcConnection jdbcConnection;
    private final HeartbeatErrorHandler errorHandler;

    public DatabaseHeartbeatImpl(JdbcConnection jdbcConnection, String heartBeatActionQuery,
                                 HeartbeatErrorHandler errorHandler) {
        this.heartBeatActionQuery = heartBeatActionQuery;
        this.jdbcConnection = jdbcConnection;
        this.errorHandler = errorHandler;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void forcedBeat(Map<String, ?> partition, Map<String, ?> offset, BlockingConsumer<SourceRecord> consumer) {
        try {
            jdbcConnection.execute(heartBeatActionQuery);
        }
        catch (SQLException e) {
            if (errorHandler != null) {
                errorHandler.onError(e);
            }
            LOGGER.error("Could not execute heartbeat action (Error: {})", e.getSQLState(), e);
        }
        LOGGER.debug("Executed heartbeat action query");

    }

    @Override
    public void emit(Map<String, ?> partition, OffsetContext offset) throws InterruptedException {
        forcedBeat(partition, offset.getOffset(), null);
    }

    @Override
    public void close() {
        try {
            jdbcConnection.close();
        }
        catch (SQLException e) {
            LOGGER.error("Exception while closing the heartbeat JDBC connection", e);
        }
    }
}
