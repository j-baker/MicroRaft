package io.afloatdb;

import com.google.protobuf.ByteString;
import com.typesafe.config.ConfigFactory;
import io.afloatdb.cluster.proto.AfloatDBClusterEndpoints;
import io.afloatdb.cluster.proto.AfloatDBClusterEndpointsRequest;
import io.afloatdb.cluster.proto.AfloatDBClusterEndpointsResponse;
import io.afloatdb.cluster.proto.AfloatDBClusterServiceGrpc;
import io.afloatdb.cluster.proto.AfloatDBClusterServiceGrpc.AfloatDBClusterServiceStub;
import io.afloatdb.config.AfloatDBConfig;
import io.afloatdb.config.AfloatDBEndpointConfig;
import io.afloatdb.internal.raft.impl.model.AfloatDBEndpoint;
import io.afloatdb.raft.proto.GetOp;
import io.afloatdb.raft.proto.GetResult;
import io.afloatdb.raft.proto.PutOp;
import io.afloatdb.raft.proto.SizeOp;
import io.afloatdb.raft.proto.SizeResult;
import io.afloatdb.kv.proto.Val;
import io.afloatdb.management.proto.AddRaftEndpointAddressRequest;
import io.afloatdb.management.proto.AddRaftEndpointRequest;
import io.afloatdb.management.proto.GetRaftNodeReportRequest;
import io.afloatdb.management.proto.ManagementRequestHandlerGrpc;
import io.afloatdb.management.proto.ManagementRequestHandlerGrpc.ManagementRequestHandlerBlockingStub;
import io.afloatdb.management.proto.RaftNodeReportProto;
import io.afloatdb.management.proto.RemoveRaftEndpointRequest;
import io.afloatdb.management.proto.RemoveRaftEndpointResponse;
import io.afloatdb.raft.proto.RaftEndpointProto;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.microraft.impl.RaftNodeImpl;
import io.microraft.report.RaftGroupMembers;
import io.microraft.report.RaftNodeReport;
import io.microraft.test.util.BaseTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.afloatdb.utils.AfloatDBTestUtils.CONFIG_1;
import static io.afloatdb.utils.AfloatDBTestUtils.CONFIG_2;
import static io.afloatdb.utils.AfloatDBTestUtils.CONFIG_3;
import static io.afloatdb.utils.AfloatDBTestUtils.getAnyFollower;
import static io.afloatdb.utils.AfloatDBTestUtils.getFollowers;
import static io.afloatdb.utils.AfloatDBTestUtils.getRaftGroupMembers;
import static io.afloatdb.utils.AfloatDBTestUtils.getRaftNode;
import static io.afloatdb.utils.AfloatDBTestUtils.getTerm;
import static io.afloatdb.utils.AfloatDBTestUtils.waitUntilLeaderElected;
import static io.microraft.QueryPolicy.EVENTUAL_CONSISTENCY;
import static io.microraft.test.util.AssertionUtils.eventually;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class AfloatDBTest extends BaseTest {

    private List<AfloatDB> servers = new ArrayList<>();
    private Map<String, ManagedChannel> channels = new HashMap<>();

    @Before
    public void init() {
        servers.add(AfloatDB.bootstrap(CONFIG_1));
        servers.add(AfloatDB.bootstrap(CONFIG_2));
        servers.add(AfloatDB.bootstrap(CONFIG_3));
    }

    @After
    public void tearDown() {
        servers.forEach(AfloatDB::shutdown);
        channels.values().forEach(ManagedChannel::shutdownNow);
    }

    private ManagedChannel createChannel(String address) {
        return channels.computeIfAbsent(address,
                s -> ManagedChannelBuilder.forTarget(address).usePlaintext().disableRetry().directExecutor().build());
    }

    @Test
    public void when_leaderFails_then_newLeaderElected() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        leader.shutdown();
        leader.awaitTermination();

        AfloatDB newLeader = waitUntilLeaderElected(servers);

        assertThat(newLeader.getLocalEndpoint()).isNotEqualTo(leader.getLocalEndpoint());
    }

    @Test
    public void testGetReport() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        int term = getTerm(leader);
        RaftEndpointProto leaderEndpoint = AfloatDBEndpoint.unwrap(leader.getLocalEndpoint());
        RaftGroupMembers groupMembers = getRaftGroupMembers(leader);
        List<RaftEndpointProto> endpoints = groupMembers.getMembers().stream().map(e -> (AfloatDBEndpoint) e)
                .map(AfloatDBEndpoint::getEndpoint).collect(toList());

        eventually(() -> {
            for (AfloatDB server : servers) {
                ManagementRequestHandlerBlockingStub stub = createManagementStub(server);
                RaftNodeReportProto report = stub.getRaftNodeReport(GetRaftNodeReportRequest.newBuilder().build())
                        .getReport();

                assertThat(report.getEndpoint().getId()).isEqualTo(server.getLocalEndpoint().getId());
                assertThat(report.getTerm().getTerm()).isEqualTo(term);
                assertThat(report.getTerm().getLeaderEndpoint()).isEqualTo(leaderEndpoint);
                assertThat(report.getCommittedMembers().getLogIndex()).isEqualTo(groupMembers.getLogIndex());
                assertThat(report.getCommittedMembers().getMemberList()).isEqualTo(endpoints);
            }
        });
    }

    @Test
    public void when_serverCrashesAndIsRemoved_then_newMemberListDoesNotContainRemovedServer() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDB follower = getAnyFollower(servers);
        RaftEndpointProto followerEndpoint = AfloatDBEndpoint.unwrap(follower.getLocalEndpoint());

        follower.shutdown();
        follower.awaitTermination();

        ManagementRequestHandlerBlockingStub stub = createManagementStub(leader);
        RaftNodeReportProto report = stub.getRaftNodeReport(GetRaftNodeReportRequest.newBuilder().build()).getReport();

        long groupMembersCommitIndex = report.getCommittedMembers().getLogIndex();

        RemoveRaftEndpointRequest removeEndpointRequest = RemoveRaftEndpointRequest.newBuilder()
                .setGroupMembersCommitIndex(groupMembersCommitIndex).setEndpoint(followerEndpoint).build();

        RemoveRaftEndpointResponse removeEndpointResponse = stub.removeRaftEndpoint(removeEndpointRequest);

        assertThat(removeEndpointResponse.getGroupMembersCommitIndex()).isGreaterThan(groupMembersCommitIndex);

        report = stub.getRaftNodeReport(GetRaftNodeReportRequest.newBuilder().build()).getReport();

        assertThat(report.getCommittedMembers().getLogIndex())
                .isEqualTo(removeEndpointResponse.getGroupMembersCommitIndex());
        assertThat(report.getCommittedMembers().getMemberList()).doesNotContain(followerEndpoint);
    }

    @Test
    public void when_removeEndpointInvokedOnFollower_then_cannotRemoveEndpoint() {
        waitUntilLeaderElected(servers);
        AfloatDB follower = getAnyFollower(servers);
        RaftEndpointProto followerEndpoint = AfloatDBEndpoint.unwrap(follower.getLocalEndpoint());

        ManagementRequestHandlerBlockingStub stub = createManagementStub(follower);
        RaftNodeReportProto report = stub.getRaftNodeReport(GetRaftNodeReportRequest.newBuilder().build()).getReport();
        long groupMembersCommitIndex = report.getCommittedMembers().getLogIndex();

        RemoveRaftEndpointRequest removeEndpointRequest = RemoveRaftEndpointRequest.newBuilder()
                .setGroupMembersCommitIndex(groupMembersCommitIndex).setEndpoint(followerEndpoint).build();
        try {
            stub.removeRaftEndpoint(removeEndpointRequest);
            fail();
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode()).isSameAs(Status.FAILED_PRECONDITION.getCode());
        }
    }

    @Test
    public void when_removeEndpointInvokedWithWrongGroupMembersCommitIndex_then_cannotRemoveEndpoint() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDB follower = getAnyFollower(servers);
        RaftEndpointProto followerEndpoint = AfloatDBEndpoint.unwrap(follower.getLocalEndpoint());

        RemoveRaftEndpointRequest removeEndpointRequest = RemoveRaftEndpointRequest.newBuilder()
                .setGroupMembersCommitIndex(-1).setEndpoint(followerEndpoint).build();
        try {
            createManagementStub(leader).removeRaftEndpoint(removeEndpointRequest);
            fail();
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode()).isSameAs(Status.INVALID_ARGUMENT.getCode());
        }
    }

    @Test
    public void when_joinTriggeredViaLeader_then_newServerAddedToTheRaftGroup() {
        testJoin(waitUntilLeaderElected(servers));
    }

    private void testJoin(AfloatDB server) {
        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + server.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDB newServer = AfloatDB.join(AfloatDBConfig.from(ConfigFactory.parseString(configString)), true);
        servers.add(newServer);

        AfloatDB leader = waitUntilLeaderElected(servers);
        RaftNodeReport leaderReport = leader.getRaftNodeReport();
        assertThat(leaderReport.getCommittedMembers().getMembers()).contains(newServer.getLocalEndpoint());

        eventually(() -> {
            RaftNodeReport newServerReport = newServer.getRaftNodeReport();
            assertThat(newServerReport.getCommittedMembers().getMembers())
                    .isEqualTo(leaderReport.getCommittedMembers().getMembers());
            assertThat(newServerReport.getTerm().getTerm()).isEqualTo(leaderReport.getTerm().getTerm());
        });

        PutOp put = PutOp.newBuilder().setKey("key").setVal(Val.newBuilder().setStr("val").build()).build();

        getRaftNode(leader).replicate(put).join();

        eventually(() -> {
            GetOp get = GetOp.newBuilder().setKey("key").build();
            GetResult result = getRaftNode(newServer).<GetResult> query(get, EVENTUAL_CONSISTENCY, 0).join()
                    .getResult();
            assertThat(result.getVal()).isEqualTo(put.getVal());
        });
    }

    @Test
    public void when_joinTriggeredViaFollower_then_newServerAddedToTheRaftGroup() {
        waitUntilLeaderElected(servers);
        testJoin(getAnyFollower(servers));
    }

    @Test(expected = AfloatDBException.class)
    public void when_thereIsCrashedServer_then_cannotJoinNewServer() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDB crashedFollower = getAnyFollower(servers);
        crashedFollower.shutdown();
        crashedFollower.awaitTermination();

        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + leader.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDB.join(AfloatDBConfig.from(ConfigFactory.parseString(configString)), true);
    }

    @Test
    public void when_crashedFollowerIsRemoved_then_newServerCanJoin() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDB crashedFollower = getAnyFollower(servers);
        crashedFollower.shutdown();
        crashedFollower.awaitTermination();

        RaftEndpointProto crashedFollowerEndpoint = AfloatDBEndpoint.unwrap(crashedFollower.getLocalEndpoint());
        RemoveRaftEndpointRequest removeEndpointRequest = RemoveRaftEndpointRequest.newBuilder()
                .setGroupMembersCommitIndex(0).setEndpoint(crashedFollowerEndpoint).build();

        createManagementStub(leader).removeRaftEndpoint(removeEndpointRequest);

        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + leader.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDB newServer = AfloatDB.join(AfloatDBConfig.from(ConfigFactory.parseString(configString)), true);
        servers.add(newServer);

        RaftNodeReport leaderReport = leader.getRaftNodeReport();
        assertThat(leaderReport.getCommittedMembers().getMembers()).contains(newServer.getLocalEndpoint());

        eventually(() -> {
            RaftNodeReport newServerReport = newServer.getRaftNodeReport();
            assertThat(newServerReport.getCommittedMembers().getMembers())
                    .isEqualTo(leaderReport.getCommittedMembers().getMembers());
            assertThat(newServerReport.getTerm().getTerm()).isEqualTo(leaderReport.getTerm().getTerm());
        });
    }

    @Test
    public void when_newServerCrashesJustAfterJoin_then_itCanRejoin() {
        AfloatDB leader = waitUntilLeaderElected(servers);

        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + leader.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDBConfig newServerConfig = AfloatDBConfig.from(ConfigFactory.parseString(configString));

        RaftEndpointProto newServerEndpoint = RaftEndpointProto.newBuilder().setId("node4").build();

        for (AfloatDB server : servers) {
            AddRaftEndpointAddressRequest request = AddRaftEndpointAddressRequest.newBuilder()
                    .setEndpoint(newServerEndpoint).setAddress("localhost:6704").build();
            createManagementStub(server).addRaftEndpointAddress(request);
        }

        AddRaftEndpointRequest addRaftEndpointRequest = AddRaftEndpointRequest.newBuilder()
                .setEndpoint(newServerEndpoint).setGroupMembersCommitIndex(0).build();
        createManagementStub(leader).addRaftEndpoint(addRaftEndpointRequest);

        RaftNodeReport leaderReport = leader.getRaftNodeReport();
        assertThat(leaderReport.getCommittedMembers().getMembers()).hasSize(4);

        AfloatDB newServer = AfloatDB.join(newServerConfig, true);
        servers.add(newServer);

        eventually(() -> {
            RaftNodeReport newServerReport = newServer.getRaftNodeReport();
            assertThat(newServerReport.getCommittedMembers().getMembers())
                    .isEqualTo(leaderReport.getCommittedMembers().getMembers());
            assertThat(newServerReport.getTerm().getTerm()).isEqualTo(leaderReport.getTerm().getTerm());
        });
    }

    @Test
    public void when_newServerJoinsAfterLeaderTakesSnapshot_then_newServerInstallsSnapshot() {
        AfloatDB leader = waitUntilLeaderElected(servers);

        RaftNodeImpl raftNode = (RaftNodeImpl) getRaftNode(leader);
        int keyCount = leader.getConfig().getRaftConfig().getCommitCountToTakeSnapshot();
        for (int keyIndex = 1; keyIndex <= keyCount; keyIndex++) {
            String key = "key" + keyIndex;
            PutOp put = PutOp.newBuilder().setKey(key).setVal(Val.newBuilder().setStr(key).build()).build();
            raftNode.replicate(put).join();
        }

        eventually(() -> {
            for (AfloatDB follower : getFollowers(servers)) {
                assertThat(follower.getRaftNodeReport().getLog().getTakeSnapshotCount()).isGreaterThan(0);
            }
        });

        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + leader.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDB newServer = AfloatDB.join(AfloatDBConfig.from(ConfigFactory.parseString(configString)), true);
        servers.add(newServer);

        eventually(() -> {
            SizeResult result = getRaftNode(newServer)
                    .<SizeResult> query(SizeOp.getDefaultInstance(), EVENTUAL_CONSISTENCY, 0).join().getResult();
            assertThat(result.getSize()).isEqualTo(keyCount);
        });
    }

    @Test(expected = AfloatDBException.class)
    public void when_joinConfigProvided_then_cannotBootstrapNewServer() {
        AfloatDB leader = waitUntilLeaderElected(servers);

        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + leader.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDB.bootstrap(AfloatDBConfig.from(ConfigFactory.parseString(configString)));
    }

    @Test(expected = AfloatDBException.class)
    public void when_bootstrapConfigProvided_then_cannotJoin() {
        AfloatDB.join(CONFIG_3, true);
    }

    @Test
    public void when_observerStubConnects_then_itGetsCurrentGroupMembersImmediately() throws InterruptedException {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDBClusterServiceStub stub = createAfloatDBClusterServiceStub(leader);

        AtomicReference<AfloatDBClusterEndpoints> endpointsRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        AfloatDBClusterEndpointsRequest request = AfloatDBClusterEndpointsRequest.newBuilder().setClientId("client1")
                .build();
        stub.listenClusterEndpoints(request, new StreamObserver<AfloatDBClusterEndpointsResponse>() {
            @Override
            public void onNext(AfloatDBClusterEndpointsResponse response) {
                endpointsRef.set(response.getEndpoints());
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();

        AfloatDBClusterEndpoints endpoints = endpointsRef.get();

        List<AfloatDBEndpointConfig> initialEndpoints = leader.getConfig().getRaftGroupConfig().getInitialEndpoints();
        assertThat(endpoints.getEndpointCount()).isEqualTo(initialEndpoints.size());

        for (AfloatDBEndpointConfig endpointConfig : leader.getConfig().getRaftGroupConfig().getInitialEndpoints()) {
            String address = endpoints.getEndpointMap().get(endpointConfig.getId());
            assertThat(address).isNotNull().isEqualTo(endpointConfig.getAddress());
        }
    }

    @Test
    public void when_crashedServerIsRemoved_then_observerStubGetsNotified() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDBClusterServiceStub stub = createAfloatDBClusterServiceStub(leader);

        AtomicReference<AfloatDBClusterEndpoints> endpointsRef = new AtomicReference<>();

        AfloatDBClusterEndpointsRequest request = AfloatDBClusterEndpointsRequest.newBuilder().setClientId("client1")
                .build();
        stub.listenClusterEndpoints(request, new StreamObserver<AfloatDBClusterEndpointsResponse>() {
            @Override
            public void onNext(AfloatDBClusterEndpointsResponse response) {
                endpointsRef.set(response.getEndpoints());
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        eventually(() -> assertThat(endpointsRef.get()).isNotNull());

        AfloatDB follower = getAnyFollower(servers);
        follower.shutdown();
        follower.awaitTermination();

        createManagementStub(leader).removeRaftEndpoint(RemoveRaftEndpointRequest.newBuilder()
                .setEndpoint(AfloatDBEndpoint.unwrap(follower.getLocalEndpoint())).build());

        eventually(() -> {
            AfloatDBClusterEndpoints endpoints = endpointsRef.get();
            assertThat(endpoints).isNotNull();
            List<AfloatDBEndpointConfig> initialEndpoints = leader.getConfig().getRaftGroupConfig()
                    .getInitialEndpoints();
            assertThat(endpoints.getEndpointMap().size()).isEqualTo(initialEndpoints.size() - 1);
            assertThat(endpoints.getEndpointMap()).doesNotContainKey((String) follower.getLocalEndpoint().getId());
        });
    }

    @Test
    public void when_newServerJoins_then_observerStubGetsNotified() {
        AfloatDB leader = waitUntilLeaderElected(servers);
        AfloatDBClusterServiceStub stub = createAfloatDBClusterServiceStub(leader);

        AtomicReference<AfloatDBClusterEndpoints> endpointsRef = new AtomicReference<>();

        AfloatDBClusterEndpointsRequest request = AfloatDBClusterEndpointsRequest.newBuilder().setClientId("client1")
                .build();
        stub.listenClusterEndpoints(request, new StreamObserver<AfloatDBClusterEndpointsResponse>() {
            @Override
            public void onNext(AfloatDBClusterEndpointsResponse response) {
                endpointsRef.set(response.getEndpoints());
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        eventually(() -> assertThat(endpointsRef.get()).isNotNull());

        String configString = "afloatdb.local-endpoint.id: \"node4\"\nafloatdb.local-endpoint.address: "
                + "\"localhost:6704\"\n" + "afloatdb.group.id: \"test\"\nafloatdb.group.join-to: \""
                + leader.getConfig().getLocalEndpointConfig().getAddress() + "\"";

        AfloatDB newServer = AfloatDB.join(AfloatDBConfig.from(ConfigFactory.parseString(configString)), true);
        servers.add(newServer);

        eventually(() -> {
            AfloatDBClusterEndpoints endpoints = endpointsRef.get();
            assertThat(endpoints).isNotNull();
            List<AfloatDBEndpointConfig> initialEndpoints = leader.getConfig().getRaftGroupConfig()
                    .getInitialEndpoints();
            assertThat(endpoints.getEndpointMap().size()).isEqualTo(initialEndpoints.size() + 1);
            assertThat(endpoints.getEndpointMap()).containsEntry("node4", "localhost:6704");
        });
    }

    private ManagementRequestHandlerBlockingStub createManagementStub(AfloatDB server) {
        return ManagementRequestHandlerGrpc
                .newBlockingStub(createChannel(server.getConfig().getLocalEndpointConfig().getAddress()));
    }

    private AfloatDBClusterServiceStub createAfloatDBClusterServiceStub(AfloatDB server) {
        return AfloatDBClusterServiceGrpc
                .newStub(createChannel(server.getConfig().getLocalEndpointConfig().getAddress()));
    }

}
