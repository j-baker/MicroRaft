/*
 * Copyright (c) 2020, MicroRaft.
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

import io.microraft.MembershipChangeMode;
import io.microraft.Ordered;
import io.microraft.QueryPolicy;
import io.microraft.RaftConfig;
import io.microraft.RaftNode;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.model.log.BaseLogEntry;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.persistence.RaftStore;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.report.RaftGroupMembers;
import io.microraft.test.util.BaseTest;
import org.junit.After;
import org.junit.Test;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static io.microraft.impl.local.LocalRaftGroup.IN_MEMORY_RAFT_STATE_STORE_FACTORY;
import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.impl.local.SimpleStateMachine.queryLastValue;
import static io.microraft.test.util.AssertionUtils.allTheTime;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.getCommitIndex;
import static io.microraft.test.util.RaftTestUtils.getEffectiveGroupMembers;
import static io.microraft.test.util.RaftTestUtils.getLastLogOrSnapshotEntry;
import static io.microraft.test.util.RaftTestUtils.getRaftStore;
import static io.microraft.test.util.RaftTestUtils.getRestoredState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class SingletonClusterTest
        extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test
    public void when_singletonRaftGroupIsStarted_then_leaderIsElected() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3).build();
        group = LocalRaftGroup.start(1, config);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        assertThat(leader).isNotNull();
        assertThat(leader.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
        assertThat(leader.getTerm().getTerm()).isGreaterThan(0);

        allTheTime(() -> assertThat(leader.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint()),
                   2 * config.getLeaderHeartbeatTimeoutSecs());
    }

    @Test
    public void when_singletonRaftGroupIsStarted_then_logEntryIsCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        Object val = group.getStateMachine(leader.getLocalEndpoint()).get(result.getCommitIndex());
        assertThat(val).isEqualTo(expectedVal);
    }

    @Test
    public void when_singletonRaftGroupIsStarted_then_multipleLogEntriesAreCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        List<Entry<CompletableFuture<Ordered<Object>>, String>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String expectedVal = "val" + i;
            CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal));
            futures.add(new SimpleEntry<>(future, expectedVal));
        }

        SimpleStateMachine stateMachine = group.getStateMachine(leader.getLocalEndpoint());

        for (Entry<CompletableFuture<Ordered<Object>>, String> e : futures) {
            Ordered<Object> result = e.getKey().join();
            Object val = stateMachine.get(result.getCommitIndex());
            assertThat(val).isEqualTo(e.getValue());
        }
    }

    @Test
    public void when_singletonClusterIsStartedWithRaftStore_then_logEntryIsCommitted() {
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        Object val = group.getStateMachine(leader.getLocalEndpoint()).get(result.getCommitIndex());
        assertThat(val).isEqualTo(expectedVal);
    }

    @Test
    public void when_singletonRaftGroupIsStartedWithRaftStore_then_multipleLogEntriesAreCommitted() {
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        List<Entry<CompletableFuture<Ordered<Object>>, String>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String expectedVal = "val" + i;
            CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal));
            futures.add(new SimpleEntry<>(future, expectedVal));
        }

        SimpleStateMachine stateMachine = group.getStateMachine(leader.getLocalEndpoint());

        for (Entry<CompletableFuture<Ordered<Object>>, String> e : futures) {
            Ordered<Object> result = e.getKey().join();
            Object val = stateMachine.get(result.getCommitIndex());
            assertThat(val).isEqualTo(e.getValue());
        }
    }

    @Test
    public void when_memberIsAddedToSingletonRaftGroup_then_quorumsUpdated() {
        group = LocalRaftGroup.start(1);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNodeImpl newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.getLocalEndpoint());
        assertThat(membershipChangeResult.getResult().getMajority()).isEqualTo(2);

        eventually(() -> {
            Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test
    public void when_memberIsAddedToSingletonRaftGroupWithRaftStore_then_quorumsUpdated() {
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNodeImpl newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.getLocalEndpoint());
        assertThat(membershipChangeResult.getResult().getMajority()).isEqualTo(2);

        eventually(() -> {
            Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test
    public void when_memberIsAddedToSingletonRaftGroup_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNodeImpl newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.getLocalEndpoint());
        assertThat(membershipChangeResult.getResult().getMajority()).isEqualTo(2);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test
    public void when_memberIsAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryIsCommitted() {
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNodeImpl newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.getLocalEndpoint());
        assertThat(membershipChangeResult.getResult().getMajority()).isEqualTo(2);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test
    public void when_memberIsAddedToSingletonRaftGroup_then_newLogEntryCannotBeCommittedOnlyWithLeader() {
        group = LocalRaftGroup.start(1);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        leader.replicate(applyValue(expectedVal1)).join();

        RaftNodeImpl newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.getLocalEndpoint());
        assertThat(membershipChangeResult.getResult().getMajority()).isEqualTo(2);

        group.dropMessagesTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint(), AppendEntriesRequest.class);

        String expectedVal2 = "val2";
        CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal2));

        allTheTime(() -> {
            assertThat(getCommitIndex(leader)).isEqualTo(membershipChangeResult.getCommitIndex());
        }, 3);

        group.allowMessagesTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint(), AppendEntriesRequest.class);

        Ordered<Object> result2 = future.join();

        eventually(() -> {
            assertThat(getCommitIndex(leader)).isEqualTo(result2.getCommitIndex());
            assertThat(getCommitIndex(newNode)).isEqualTo(result2.getCommitIndex());
            assertThat(group.getStateMachine(leader.getLocalEndpoint()).get(result2.getCommitIndex())).isEqualTo(expectedVal2);
            assertThat(group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex())).isEqualTo(expectedVal2);
        });
    }

    @Test
    public void when_memberIsAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryCannotBeCommittedOnlyWithLeader() {
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        leader.replicate(applyValue(expectedVal1)).join();

        RaftNodeImpl newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.getLocalEndpoint());
        assertThat(membershipChangeResult.getResult().getMajority()).isEqualTo(2);

        group.dropMessagesTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint(), AppendEntriesRequest.class);

        String expectedVal2 = "val2";
        CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal2));

        allTheTime(() -> {
            assertThat(getCommitIndex(leader)).isEqualTo(membershipChangeResult.getCommitIndex());
        }, 3);

        group.allowMessagesTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint(), AppendEntriesRequest.class);

        Ordered<Object> result2 = future.join();

        eventually(() -> {
            assertThat(getCommitIndex(leader)).isEqualTo(result2.getCommitIndex());
            assertThat(getCommitIndex(newNode)).isEqualTo(result2.getCommitIndex());
            assertThat(group.getStateMachine(leader.getLocalEndpoint()).get(result2.getCommitIndex())).isEqualTo(expectedVal2);
            assertThat(group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex())).isEqualTo(expectedVal2);
        });
    }

    @Test
    public void when_singletonRaftGroupIsStarted_then_linearizableQueryIsExecuted() {
        LocalRaftGroup group = LocalRaftGroup.start(1);

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();

        Ordered<Object> queryResult = leader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join();

        assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
        assertThat(queryResult.getResult()).isEqualTo(val);
    }

    @Test
    public void when_singletonRaftGroupIsStarted_then_leaderLocalQueryIsExecuted() {
        LocalRaftGroup group = LocalRaftGroup.start(1);

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();

        Ordered<Object> queryResult = leader.query(queryLastValue(), QueryPolicy.LEADER_LOCAL, 0).join();

        assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
        assertThat(queryResult.getResult()).isEqualTo(val);
    }

    @Test
    public void when_singletonRaftGroupIsStarted_then_anyLocalQueryIsExecuted() {
        LocalRaftGroup group = LocalRaftGroup.start(1);

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        Ordered<Object> queryResult = leader.query(queryLastValue(), QueryPolicy.ANY_LOCAL, 0).join();

        assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
        assertThat(queryResult.getResult()).isEqualTo(expectedVal);
    }

    @Test
    public void when_singletonRaftGroupRestarted_then_leaderIsElected() {
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                                             .enableNewTermOperation().build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        int term = leader.getTerm().getTerm();

        RestoredRaftState restoredState = getRestoredState(leader);
        RaftStore raftStore = getRaftStore(leader);

        group.terminateNode(leader.getLocalEndpoint());

        RaftNodeImpl restoredNode = group.restoreNode(restoredState, raftStore);

        eventually(() -> {
            assertThat(restoredNode.getLeaderEndpoint()).isEqualTo(restoredNode.getLocalEndpoint());
            int newTerm = restoredNode.getTerm().getTerm();
            assertThat(newTerm).isGreaterThan(term);

            BaseLogEntry entry = getLastLogOrSnapshotEntry(restoredNode);
            assertThat(entry.getTerm()).isEqualTo(newTerm);

            long commitIndex = getCommitIndex(restoredNode);
            assertThat(entry.getIndex()).isEqualTo(commitIndex);
        });

        Object queryResult = restoredNode.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join().getResult();
        assertThat(queryResult).isEqualTo(val);

        assertThat(group.getStateMachine(restoredNode.getLocalEndpoint()).get(result.getCommitIndex())).isEqualTo(val);
    }

    @Test
    public void when_nodeRestartsAfterSingletonRaftGroupExpanded_then_newLeaderIsElected() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3).build();
        LocalRaftGroup group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                                             .enableNewTermOperation().setConfig(config).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        int term = leader.getTerm().getTerm();

        RaftNodeImpl newNode = group.createNewNode();

        leader.changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD, 0).join();

        RestoredRaftState restoredState = getRestoredState(leader);
        RaftStore raftStore = getRaftStore(leader);

        group.terminateNode(leader.getLocalEndpoint());

        RaftNodeImpl restoredNode = group.restoreNode(restoredState, raftStore);

        group.waitUntilLeaderElected();

        eventually(() -> {
            for (RaftNodeImpl node : Arrays.asList(newNode, restoredNode)) {
                int newTerm = node.getTerm().getTerm();
                assertThat(newTerm).isGreaterThan(term);

                BaseLogEntry entry = getLastLogOrSnapshotEntry(node);
                assertThat(entry.getTerm()).isEqualTo(newTerm);

                long commitIndex = getCommitIndex(node);
                assertThat(entry.getIndex()).isEqualTo(commitIndex);
            }
        });

        Object queryResult = restoredNode.query(queryLastValue(), QueryPolicy.ANY_LOCAL, 0).join().getResult();
        assertThat(queryResult).isEqualTo(val);

        assertThat(group.getStateMachine(restoredNode.getLocalEndpoint()).get(result.getCommitIndex())).isEqualTo(val);
    }

    @Test
    public void when_followerLeaves2NodeRaftGroup_then_singletonRaftGroupCommitsNewLogEntry() {
        LocalRaftGroup group = LocalRaftGroup.start(2);

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNode follower = group.getAnyFollower();

        String val1 = "val1";
        leader.replicate(applyValue(val1)).join();

        Ordered<RaftGroupMembers> mewGroupMembers = leader
                .changeMembership(follower.getLocalEndpoint(), MembershipChangeMode.REMOVE, 0).join();

        follower.terminate();

        assertThat(mewGroupMembers.getResult().getMembers().size()).isEqualTo(1);
        assertThat(mewGroupMembers.getResult().getMembers()).contains(leader.getLocalEndpoint());

        Ordered<Object> queryResult1 = leader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join();
        assertThat(queryResult1.getResult()).isEqualTo(val1);

        String val2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(val2)).join();

        assertThat(result2.getCommitIndex()).isGreaterThan(queryResult1.getCommitIndex());

        Ordered<Object> queryResult2 = leader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join();
        assertThat(queryResult2.getResult()).isEqualTo(val2);

        assertThat(queryResult2.getCommitIndex()).isEqualTo(result2.getCommitIndex());
    }

    @Test
    public void when_leaderLeaves2NodeRaftGroup_then_singletonRaftGroupCommitsNewLogEntry() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3).build();
        LocalRaftGroup group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().setConfig(config).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl follower = group.getAnyFollower();

        String val1 = "val1";
        leader.replicate(applyValue(val1)).join();

        int term = leader.getTerm().getTerm();

        Ordered<RaftGroupMembers> newGroupMembers = leader
                .changeMembership(leader.getLocalEndpoint(), MembershipChangeMode.REMOVE, 0).join();

        leader.terminate();

        assertThat(newGroupMembers.getResult().getMembers().size()).isEqualTo(1);
        assertThat(newGroupMembers.getResult().getMembers()).contains(follower.getLocalEndpoint());

        eventually(() -> {
            assertThat(follower.getLeaderEndpoint()).isEqualTo(follower.getLocalEndpoint());
            int newTerm = follower.getTerm().getTerm();
            assertThat(newTerm).isGreaterThan(term);
            assertThat(getCommitIndex(follower)).isGreaterThan(newGroupMembers.getCommitIndex());
        });

        RaftNodeImpl newLeader = follower;

        Ordered<Object> queryResult1 = newLeader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join();
        assertThat(queryResult1.getResult()).isEqualTo(val1);

        String val2 = "val2";
        Ordered<Object> result2 = newLeader.replicate(applyValue(val2)).join();

        assertThat(result2.getCommitIndex()).isGreaterThan(queryResult1.getCommitIndex());

        Ordered<Object> queryResult2 = newLeader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join();
        assertThat(queryResult2.getResult()).isEqualTo(val2);

        assertThat(queryResult2.getCommitIndex()).isEqualTo(result2.getCommitIndex());
    }

    @Test
    public void when_memberRemovalIsNotCommitted_then_singletonFollowerCompletesMembershipChange() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3).build();
        LocalRaftGroup group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().setConfig(config).build();

        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl follower = group.getAnyFollower();

        String val1 = "val1";
        leader.replicate(applyValue(val1)).join();

        int term = leader.getTerm().getTerm();

        group.dropMessagesTo(follower.getLocalEndpoint(), leader.getLocalEndpoint(), AppendEntriesSuccessResponse.class);

        leader.changeMembership(leader.getLocalEndpoint(), MembershipChangeMode.REMOVE, 0);

        eventually(() -> assertThat(getEffectiveGroupMembers(follower).memberCount()).isEqualTo(1));

        group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            assertThat(follower.getLeaderEndpoint()).isEqualTo(follower.getLocalEndpoint());
            int newTerm = follower.getTerm().getTerm();
            assertThat(newTerm).isGreaterThan(term);
        });

        RaftNodeImpl newLeader = follower;

        String val2 = "val2";
        Ordered<Object> result2 = newLeader.replicate(applyValue(val2)).join();

        Ordered<Object> queryResult = newLeader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0).join();
        assertThat(queryResult.getResult()).isEqualTo(val2);

        assertThat(queryResult.getCommitIndex()).isEqualTo(result2.getCommitIndex());
    }

    @Test
    public void when_singletonRaftGroupIsRunning_then_cannotRemoveEndpoint() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();

        try {
            leader.changeMembership(leader.getLocalEndpoint(), MembershipChangeMode.REMOVE, 0).join();
            fail("Cannot remove self from singleton Raft group");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

}