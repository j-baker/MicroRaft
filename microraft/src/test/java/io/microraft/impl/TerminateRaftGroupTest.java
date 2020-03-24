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

package io.microraft.impl;

import io.microraft.RaftEndpoint;
import io.microraft.exception.CannotReplicateException;
import io.microraft.exception.NotLeaderException;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.util.BaseTest;
import io.microraft.model.message.AppendEntriesRequest;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

import static io.microraft.RaftNodeStatus.ACTIVE;
import static io.microraft.RaftNodeStatus.TERMINATED;
import static io.microraft.RaftNodeStatus.TERMINATING_RAFT_GROUP;
import static io.microraft.impl.local.SimpleStateMachine.apply;
import static io.microraft.impl.util.AssertionUtils.eventually;
import static io.microraft.impl.util.RaftTestUtils.getStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

/**
 * @author mdogan
 * @author metanet
 */
public class TerminateRaftGroupTest
        extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_terminateOpIsAppendedButNotCommitted_then_cannotAppendNewEntry()
            throws Exception {
        group = new LocalRaftGroup(2);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl follower = group.getAnyFollowerNode();

        group.dropAllMessagesToMember(leader.getLocalEndpoint(), follower.getLocalEndpoint());

        leader.terminateGroup();

        try {
            leader.replicate(apply("val")).get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e).hasCauseInstanceOf(CannotReplicateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_terminateOpIsAppended_then_statusIsTerminating() {
        group = new LocalRaftGroup(2);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl follower = group.getAnyFollowerNode();

        group.dropAllMessagesToMember(follower.getLocalEndpoint(), leader.getLocalEndpoint());

        leader.terminateGroup();

        eventually(() -> {
            assertThat(getStatus(leader)).isEqualTo(TERMINATING_RAFT_GROUP);
            assertThat(getStatus(follower)).isEqualTo(TERMINATING_RAFT_GROUP);
        });
    }

    @Test(timeout = 300_000)
    public void when_terminateOpIsCommitted_then_raftNodeIsTerminated()
            throws Exception {
        group = new LocalRaftGroup(2);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl follower = group.getAnyFollowerNode();

        long commitIndex = leader.replicate(apply("val")).get().getCommitIndex();

        long terminationCommitIndex = leader.terminateGroup().get().getCommitIndex();
        assertThat(terminationCommitIndex).isEqualTo(commitIndex + 1);
        assertThat(leader.getStatus()).isEqualTo(TERMINATED);

        eventually(() -> {
            assertThat(follower.state().commitIndex()).isEqualTo(terminationCommitIndex);
            assertThat(follower.getStatus()).isEqualTo(TERMINATED);
        });

        try {
            leader.replicate(apply("val")).get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
        }

        try {
            follower.replicate(apply("val")).get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_terminateOpIsTruncated_then_statusIsActive()
            throws Exception {
        group = new LocalRaftGroup(3);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());

        group.dropMessagesToAll(leader.getLocalEndpoint(), AppendEntriesRequest.class);

        leader.terminateGroup();

        group.splitMembers(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : followers) {
                RaftEndpoint leaderEndpoint = raftNode.getLeaderEndpoint();
                assertThat(leaderEndpoint).isNotNull().isNotEqualTo(leader.getLocalEndpoint());
            }
        });

        RaftNodeImpl newLeader = group.getNode(followers[0].getLeaderEndpoint());

        for (int i = 0; i < 10; i++) {
            newLeader.replicate(apply("val" + i)).get();
        }

        group.merge();

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getStatus(raftNode)).isEqualTo(ACTIVE);
            }
        });
    }

}