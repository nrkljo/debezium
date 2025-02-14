/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.postgresql;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.AbstractSourceInfo;
import io.debezium.connector.SnapshotRecord;
import io.debezium.connector.SnapshotType;
import io.debezium.connector.postgresql.connection.Lsn;
import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.connector.postgresql.connection.ReplicationMessage.Operation;
import io.debezium.connector.postgresql.spi.OffsetState;
import io.debezium.pipeline.CommonOffsetContext;
import io.debezium.pipeline.source.snapshot.incremental.IncrementalSnapshotContext;
import io.debezium.pipeline.source.snapshot.incremental.SignalBasedIncrementalSnapshotContext;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.txmetadata.TransactionContext;
import io.debezium.relational.TableId;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.time.Conversions;
import io.debezium.util.Clock;

public class PostgresOffsetContext extends CommonOffsetContext<SourceInfo> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresOffsetContext.class);

    public static final String LAST_COMPLETELY_PROCESSED_LSN_KEY = "lsn_proc";
    public static final String LAST_COMMIT_LSN_KEY = "lsn_commit";

    private final Schema sourceInfoSchema;
    private boolean lastSnapshotRecord;
    private Lsn lastCompletelyProcessedLsn;
    private Lsn lastCommitLsn;
    private Lsn streamingStoppingLsn = null;
    private final TransactionContext transactionContext;
    private final IncrementalSnapshotContext<TableId> incrementalSnapshotContext;

    private PostgresOffsetContext(PostgresConnectorConfig connectorConfig, Lsn lsn, Lsn lastCompletelyProcessedLsn, Lsn lastCommitLsn, Long txId, Operation messageType,
                                  Instant time,
                                  SnapshotType snapshot,
                                  boolean lastSnapshotRecord, boolean snapshotCompleted, TransactionContext transactionContext,
                                  IncrementalSnapshotContext<TableId> incrementalSnapshotContext) {
        super(new SourceInfo(connectorConfig), snapshotCompleted);

        this.lastCompletelyProcessedLsn = lastCompletelyProcessedLsn;
        this.lastCommitLsn = lastCommitLsn;
        sourceInfo.update(lsn, time, txId, sourceInfo.xmin(), null, messageType);
        sourceInfo.updateLastCommit(lastCommitLsn);
        sourceInfoSchema = sourceInfo.schema();

        this.lastSnapshotRecord = lastSnapshotRecord;
        if (this.lastSnapshotRecord || this.snapshotCompleted) {
            postSnapshotCompletion();
        }
        else {
            setSnapshot(snapshot);
            sourceInfo.setSnapshot(snapshot != null ? SnapshotRecord.TRUE : SnapshotRecord.FALSE);
        }
        this.transactionContext = transactionContext;
        this.incrementalSnapshotContext = incrementalSnapshotContext;
    }

    @Override
    public Map<String, ?> getOffset() {
        Map<String, Object> result = new HashMap<>();
        if (sourceInfo.timestamp() != null) {
            result.put(SourceInfo.TIMESTAMP_USEC_KEY, Conversions.toEpochMicros(sourceInfo.timestamp()));
        }
        if (sourceInfo.txId() != null) {
            result.put(SourceInfo.TXID_KEY, sourceInfo.txId());
        }
        if (sourceInfo.lsn() != null) {
            result.put(SourceInfo.LSN_KEY, sourceInfo.lsn().asLong());
        }
        if (sourceInfo.xmin() != null) {
            result.put(SourceInfo.XMIN_KEY, sourceInfo.xmin());
        }
        if (getSnapshot().isPresent()) {
            result.put(AbstractSourceInfo.SNAPSHOT_KEY, getSnapshot().get().toString());
            result.put(SourceInfo.LAST_SNAPSHOT_RECORD_KEY, lastSnapshotRecord);
            result.put(SNAPSHOT_COMPLETED_KEY, snapshotCompleted);
        }
        if (lastCompletelyProcessedLsn != null) {
            result.put(LAST_COMPLETELY_PROCESSED_LSN_KEY, lastCompletelyProcessedLsn.asLong());
        }
        if (lastCommitLsn != null) {
            result.put(LAST_COMMIT_LSN_KEY, lastCommitLsn.asLong());
        }
        if (sourceInfo.messageType() != null) {
            result.put(SourceInfo.MSG_TYPE_KEY, sourceInfo.messageType().toString());
        }
        return sourceInfo.isSnapshot() ? result : incrementalSnapshotContext.store(transactionContext.store(result));
    }

    @Override
    public Schema getSourceInfoSchema() {
        return sourceInfoSchema;
    }

    @Override
    public void preSnapshotStart(boolean onDemand) {
        super.preSnapshotStart(onDemand);
        lastSnapshotRecord = false;
    }

    @Override
    public void preSnapshotCompletion() {
        super.preSnapshotCompletion();
        lastSnapshotRecord = true;
    }

    public void updateWalPosition(Lsn lsn, Lsn lastCompletelyProcessedLsn, Instant commitTime, Long txId, Long xmin, TableId tableId, Operation messageType) {
        this.lastCompletelyProcessedLsn = lastCompletelyProcessedLsn;
        sourceInfo.update(lsn, commitTime, txId, xmin, tableId, messageType);
    }

    /**
     * update wal position for lsn events that do not have an associated table or schema
     */
    public void updateWalPosition(Lsn lsn, Lsn lastCompletelyProcessedLsn, Instant commitTime, Long txId, Long xmin, Operation messageType) {
        updateWalPosition(lsn, lastCompletelyProcessedLsn, commitTime, txId, xmin, null, messageType);
    }

    public void updateCommitPosition(Lsn lsn, Lsn lastCompletelyProcessedLsn) {
        this.lastCompletelyProcessedLsn = lastCompletelyProcessedLsn;
        this.lastCommitLsn = lsn;
        sourceInfo.updateLastCommit(lsn);
    }

    boolean hasLastKnownPosition() {
        return sourceInfo.lsn() != null;
    }

    boolean hasCompletelyProcessedPosition() {
        return this.lastCompletelyProcessedLsn != null;
    }

    Lsn lsn() {
        return sourceInfo.lsn();
    }

    Lsn lastCompletelyProcessedLsn() {
        return lastCompletelyProcessedLsn;
    }

    public Lsn lastCommitLsn() {
        return lastCommitLsn;
    }

    Operation lastProcessedMessageType() {
        return sourceInfo.messageType();
    }

    /**
     * Returns the LSN that the streaming phase should stream events up to or null if
     * a stopping point is not set. If set during the streaming phase, any event with
     * an LSN less than the stopping LSN will be processed and once the stopping LSN
     * is reached, the streaming phase will end. Useful for a pre-snapshot catch up
     * streaming phase.
     */
    Lsn getStreamingStoppingLsn() {
        return streamingStoppingLsn;
    }

    public void setStreamingStoppingLsn(Lsn streamingStoppingLsn) {
        this.streamingStoppingLsn = streamingStoppingLsn;
    }

    Long xmin() {
        return sourceInfo.xmin();
    }

    public static class Loader implements OffsetContext.Loader<PostgresOffsetContext> {

        private final PostgresConnectorConfig connectorConfig;

        public Loader(PostgresConnectorConfig connectorConfig) {
            this.connectorConfig = connectorConfig;
        }

        private Long readOptionalLong(Map<String, ?> offset, String key) {
            final Object obj = offset.get(key);
            return (obj == null) ? null : ((Number) obj).longValue();
        }

        @SuppressWarnings("unchecked")
        @Override
        public PostgresOffsetContext load(Map<String, ?> offset) {
            final Lsn lsn = Lsn.valueOf(readOptionalLong(offset, SourceInfo.LSN_KEY));
            final Lsn lastCompletelyProcessedLsn = Lsn.valueOf(readOptionalLong(offset, LAST_COMPLETELY_PROCESSED_LSN_KEY));
            Lsn lastCommitLsn = Lsn.valueOf(readOptionalLong(offset, LAST_COMMIT_LSN_KEY));
            if (lastCommitLsn == null) {
                lastCommitLsn = lastCompletelyProcessedLsn;
            }
            final Long txId = readOptionalLong(offset, SourceInfo.TXID_KEY);
            final String msgType = (String) offset.getOrDefault(SourceInfo.MSG_TYPE_KEY, null);
            final Operation messageType = msgType == null ? null : Operation.valueOf(msgType);
            final Instant useconds = Conversions.toInstantFromMicros((Long) ((Map<String, Object>) offset).getOrDefault(SourceInfo.TIMESTAMP_USEC_KEY, 0L));
            final SnapshotType snapshot = loadSnapshot(offset).orElse(null);
            boolean snapshotCompleted = loadSnapshotCompleted(offset);
            final boolean lastSnapshotRecord = (boolean) ((Map<String, Object>) offset).getOrDefault(SourceInfo.LAST_SNAPSHOT_RECORD_KEY, Boolean.FALSE);
            return new PostgresOffsetContext(connectorConfig, lsn,
                    lastCompletelyProcessedLsn, lastCommitLsn, txId, messageType, useconds, snapshot, lastSnapshotRecord,
                    snapshotCompleted,
                    TransactionContext.load(offset),
                    connectorConfig.isReadOnlyConnection()
                            ? PostgresReadOnlyIncrementalSnapshotContext.load(offset)
                            : SignalBasedIncrementalSnapshotContext.load(offset, false));
        }

    }

    @Override
    public String toString() {
        return "PostgresOffsetContext [sourceInfoSchema=" + sourceInfoSchema + ", sourceInfo=" + sourceInfo
                + ", lastSnapshotRecord=" + lastSnapshotRecord
                + ", lastCompletelyProcessedLsn=" + lastCompletelyProcessedLsn + ", lastCommitLsn=" + lastCommitLsn
                + ", streamingStoppingLsn=" + streamingStoppingLsn + ", transactionContext=" + transactionContext
                + ", incrementalSnapshotContext=" + incrementalSnapshotContext + "]";
    }

    public static PostgresOffsetContext initialContext(PostgresConnectorConfig connectorConfig, PostgresConnection jdbcConnection, Clock clock) {
        return initialContext(connectorConfig, jdbcConnection, clock, null, null);
    }

    public static PostgresOffsetContext initialContext(PostgresConnectorConfig connectorConfig, PostgresConnection jdbcConnection, Clock clock, Lsn lastCommitLsn,
                                                       Lsn lastCompletelyProcessedLsn) {
        try {
            LOGGER.info("Creating initial offset context");
            final Lsn lsn = Lsn.valueOf(jdbcConnection.currentXLogLocation());
            final Long txId = jdbcConnection.currentTransactionId();
            LOGGER.info("Read xlogStart at '{}' from transaction '{}'", lsn, txId);
            return new PostgresOffsetContext(
                    connectorConfig,
                    lsn,
                    lastCompletelyProcessedLsn,
                    lastCommitLsn,
                    txId,
                    null,
                    clock.currentTimeAsInstant(),
                    null,
                    false,
                    false,
                    new TransactionContext(),
                    connectorConfig.isReadOnlyConnection()
                            ? new PostgresReadOnlyIncrementalSnapshotContext<>()
                            : new SignalBasedIncrementalSnapshotContext<>(false));
        }
        catch (SQLException e) {
            throw new ConnectException("Database processing error", e);
        }
    }

    public OffsetState asOffsetState() {
        return new OffsetState(
                sourceInfo.lsn(),
                sourceInfo.txId(),
                sourceInfo.xmin(),
                sourceInfo.timestamp(),
                sourceInfo.isSnapshot());
    }

    @Override
    public void event(DataCollectionId tableId, Instant instant) {
        sourceInfo.update(instant, (TableId) tableId);
    }

    @Override
    public TransactionContext getTransactionContext() {
        return transactionContext;
    }

    @Override
    public IncrementalSnapshotContext<?> getIncrementalSnapshotContext() {
        return incrementalSnapshotContext;
    }
}
