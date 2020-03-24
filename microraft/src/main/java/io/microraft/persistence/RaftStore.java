/*
 * Original work Copyright (c) 2008-2020, Hazelcast, Inc.
 * Modified work Copyright 2020, MicroRaft.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.microraft.persistence;

import io.microraft.RaftConfig;
import io.microraft.RaftEndpoint;
import io.microraft.integration.StateMachine;
import io.microraft.model.log.LogEntry;
import io.microraft.model.log.SnapshotChunk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;

/**
 * This interface is used for persisting only the internal state of the Raft
 * consensus algorithm. Internal state of {@link StateMachine}
 * implementations are not persisted with this interface.
 *
 * @author mdogan
 * @author metanet
 * @author mtopolnik
 */
public interface RaftStore
        extends Closeable {

    /**
     * Initializes the store before starting to persist Raft state. This method
     * is called before any other method in this interface. After this method
     * returns, the state store must be ready to accept all other method calls.
     */
    void open()
            throws IOException;

    /**
     * Persists the given local Raft endpoint and initial Raft group members.
     * <p>
     * When this method returns, all the provided data has become durable.
     */
    void persistInitialMembers(@Nonnull RaftEndpoint localEndpoint, @Nonnull Collection<RaftEndpoint> initialMembers)
            throws IOException;

    /**
     * Persists the term and the Raft endpoint that the local Raft node voted
     * for in the given term.
     * <p>
     * When this method returns, all the provided data has become durable.
     */
    void persistTerm(int term, @Nullable RaftEndpoint votedFor)
            throws IOException;

    /**
     * Persists the given log entry.
     * <p>
     * Log entries are appended to the Raft log with sequential log indices.
     * The first log index is 1.
     * <p>
     * A block of consecutive log entries has no gaps in the indices, but a
     * gap can appear between a snapshot entry and its preceding regular log
     * entry. This happens in an edge case where a follower has fallen so far
     * behind that the missing entries are no longer available from the leader.
     * In that case the leader will send its snapshot entry instead.
     * <p>
     * In another rare failure scenario, Raft must delete a range of the highest
     * entries, rolling back the index of the next persisted entry. Consider
     * the following case where Raft persists 3 log entries and then deletes
     * entries from index=2:
     * <ul>
     *     <li> persistLogEntry(1)
     *     <li> persistLogEntry(2)
     *     <li> persistLogEntry(3)
     *     <li> truncateLogEntriesFrom(2)
     * </ul>
     * After this call sequence log indices will remain sequential and the next
     * persistLogEntry() call will be for <em>index=2</em>.
     *
     * @see #flush()
     * @see #persistSnapshotChunk(SnapshotChunk)
     * @see #truncateLogEntriesFrom(long)
     * @see RaftConfig
     */
    void persistLogEntry(@Nonnull LogEntry logEntry)
            throws IOException;

    /**
     * Persists the given snapshot chunk.
     * <p>
     * A snapshot is persisted with at least 1 chunk. The number of chunks in a
     * snapshot is provided via {@link SnapshotChunk#getSnapshotChunkCount()}.
     * A snapshot is considered to be complete when all of its chunks are
     * provided to this method and {@link #flush()} could be called afterwards.
     * <p>
     * After a snapshot is persisted at <em>index=i</em> and {@link #flush()}
     * is called, the log entry at <em>index=i</em>, all the preceding log
     * entries, and all the preceding snapshots are no longer needed and can be
     * evicted from storage. Failing to evict stale entries and snapshots do
     * not cause a consistency problem, but can increase the time to recover
     * after a crash or restart. Therefore eviction can be done in a background
     * task.
     * <p>
     * MicroRaft takes snapshots at a predetermined interval, controlled by
     * {@link RaftConfig#getCommitCountToTakeSnapshot()}.
     * For instance, if it is 100, snapshots will occur at indices 100, 200,
     * 300, and so on.
     * <p>
     * The snapshot index can lag behind the index of the highest log entry
     * that was already persisted and flushed, but there is an upper bound to
     * this difference, controlled by
     * {@link RaftConfig#getMaxUncommittedLogEntryCount()}. For instance, if it
     * is 10, and a {@code persistSnapshot()} call is made with
     * <em>snapshotIndex=100</em>, the index of the preceding
     * {@code persistLogEntry()} call can be at most 110.
     * <p>
     * On the other hand, the snapshot index can also be ahead of the highest
     * log entry. This can happen when a Raft follower has fallen so far behind
     * the leader and the leader no longer holds the missing entries. In that
     * case, the follower receives a snapshot from the leader. There is no
     * upper-bound on the gap between the highest log entry and the index of
     * the received snapshot.
     * </ul>
     *
     * @see #flush()
     * @see #persistLogEntry(LogEntry)
     * @see RaftConfig
     */
    void persistSnapshotChunk(@Nonnull SnapshotChunk snapshotChunk)
            throws IOException;

    /**
     * Rolls back the log by truncating all entries starting with the given
     * index. A truncated log entry is no longer valid and must not be restored
     * (or at least must be ignored during the restore process).
     * <p>
     * There is an upper-bound on the number of persisted log entries that can
     * be truncated afterwards, which is specified by
     * {@link RaftConfig#getMaxUncommittedLogEntryCount()} + 1. Say that it is
     * 5 and the highest persisted log entry index is 20. Then, at most 5
     * highest entries can be truncated, hence truncation can start at index=16
     * or higher.
     *
     * @see #flush()
     * @see #persistLogEntry(LogEntry)
     * @see RaftConfig
     */
    void truncateLogEntriesFrom(long logIndexInclusive)
            throws IOException;

    /**
     * Rolls back the persisted snapshot chunks only when all of the expected
     * snapshot chunks are not already persisted. A truncated snapshot chunk is
     * no longer valid and must not be restored (or at least must be ignored
     * during the restore process).
     *
     * @see #persistSnapshotChunk(SnapshotChunk)
     */
    void truncateSnapshotChunksUntil(long logIndexInclusive)
            throws IOException;

    /**
     * Forces all buffered (in any layer) Raft log changes to be written
     * to the storage layer and returns after those changes are written.
     * <p>
     * When this method returns, all the changes done via the other methods
     * have become durable.
     *
     * @see #persistLogEntry(LogEntry)
     * @see #persistSnapshotChunk(SnapshotChunk)
     * @see #truncateSnapshotChunksUntil(long)
     * @see #truncateLogEntriesFrom(long)
     */
    void flush()
            throws IOException;

}