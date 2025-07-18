/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.source.spi;

public interface ChangeEventSource {

    interface ChangeEventSourceContext {

        /**
         * Whether this source is paused.
         */
        boolean isPaused();

        /**
         * Whether this source is running or has been requested to stop.
         */
        boolean isRunning();

        /**
         * Called to indicate that the snapshot has been completed and that streaming should therefore continue.
         */
        void resumeStreaming() throws InterruptedException;

        /**
         * Wait for the resumeStreaming function to be called, which indicates that a snapshot is done
         * and that streaming should resume.
         */
        void waitSnapshotCompletion() throws InterruptedException;

        /**
         * Called by the StreamingChangeEventSource to indicate that the streaming has now been paused, and
         * that no streaming records are being processed anymore.
         */
        void streamingPaused();

        /**
         * Wait for the streamingPaused function to be called.
         */
        void waitStreamingPaused() throws InterruptedException;
    }
}
