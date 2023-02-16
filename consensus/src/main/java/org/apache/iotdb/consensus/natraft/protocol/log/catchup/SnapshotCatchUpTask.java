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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.consensus.natraft.protocol.log.catchup;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.consensus.natraft.client.AsyncRaftServiceClient;
import org.apache.iotdb.consensus.natraft.exception.LeaderUnknownException;
import org.apache.iotdb.consensus.natraft.protocol.RaftConfig;
import org.apache.iotdb.consensus.natraft.protocol.RaftRole;
import org.apache.iotdb.consensus.natraft.protocol.log.Entry;
import org.apache.iotdb.consensus.natraft.protocol.log.snapshot.Snapshot;
import org.apache.iotdb.consensus.raft.thrift.SendSnapshotRequest;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SnapshotCatchUpTask first sends the snapshot to the stale node then sends the logs to the node.
 */
public class SnapshotCatchUpTask extends LogCatchUpTask implements Callable<Boolean> {

  private static final Logger logger = LoggerFactory.getLogger(SnapshotCatchUpTask.class);

  // sending a snapshot may take longer than normal communications
  private long sendSnapshotWaitMs;
  private Snapshot snapshot;

  SnapshotCatchUpTask(
      List<Entry> logs,
      Snapshot snapshot,
      TEndPoint node,
      CatchUpManager catchUpManager,
      RaftConfig config) {
    super(logs, node, catchUpManager, config);
    this.snapshot = snapshot;
    sendSnapshotWaitMs = config.getCatchUpTimeoutMS();
  }

  private void doSnapshotCatchUp() throws TException, InterruptedException, LeaderUnknownException {
    SendSnapshotRequest request = new SendSnapshotRequest();
    request.setGroupId(raftMember.getRaftGroupId().convertToTConsensusGroupId());
    logger.info("Start to send snapshot to {}", node);
    ByteBuffer data = snapshot.serialize();
    if (logger.isInfoEnabled()) {
      logger.info("Do snapshot catch up with size {}", data.array().length);
    }
    request.setSnapshotBytes(data);

    synchronized (raftMember.getStatus().getTerm()) {
      // make sure this node is still a leader
      if (raftMember.getRole() != RaftRole.LEADER) {
        throw new LeaderUnknownException(raftMember.getAllNodes());
      }
    }

    abort = !sendSnapshotAsync(request);
  }

  @SuppressWarnings("java:S2274") // enable timeout
  private boolean sendSnapshotAsync(SendSnapshotRequest request)
      throws TException, InterruptedException {
    AtomicBoolean succeed = new AtomicBoolean(false);
    SnapshotCatchUpHandler handler = new SnapshotCatchUpHandler(succeed, node, snapshot);
    AsyncRaftServiceClient client = raftMember.getClient(node);
    if (client == null) {
      logger.info("{}: client null for node {}", raftMember.getThisNode(), node);
      abort = true;
      return false;
    }

    logger.info(
        "{}: the snapshot request size={}",
        raftMember.getName(),
        request.getSnapshotBytes().length);
    synchronized (succeed) {
      client.sendSnapshot(request, handler);
      catchUpManager.registerTask(node);
      succeed.wait(sendSnapshotWaitMs);
    }
    if (logger.isInfoEnabled()) {
      logger.info("send snapshot to node {} success {}", raftMember.getThisNode(), succeed.get());
    }
    return succeed.get();
  }

  @Override
  public Boolean call() throws InterruptedException, TException, LeaderUnknownException {
    doSnapshotCatchUp();
    if (abort) {
      logger.warn("{}: Snapshot catch up {} failed", raftMember.getName(), node);
      catchUpManager.unregisterTask(node);
      return false;
    }
    logger.info(
        "{}: Snapshot catch up {} finished, begin to catch up log", raftMember.getName(), node);
    doLogCatchUpInBatch();
    if (!abort) {
      logger.info("{}: Catch up {} finished", raftMember.getName(), node);
    } else {
      logger.warn("{}: Log catch up {} failed", raftMember.getName(), node);
    }
    // the next catch up is enabled
    catchUpManager.unregisterTask(node);
    return !abort;
  }
}