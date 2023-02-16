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

package org.apache.iotdb.consensus.natraft.service;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.consensus.ConsensusGroupId.Factory;
import org.apache.iotdb.consensus.common.request.ByteBufferConsensusRequest;
import org.apache.iotdb.consensus.natraft.RaftConsensus;
import org.apache.iotdb.consensus.natraft.exception.UnknownLogTypeException;
import org.apache.iotdb.consensus.natraft.protocol.RaftMember;
import org.apache.iotdb.consensus.raft.thrift.AppendEntriesRequest;
import org.apache.iotdb.consensus.raft.thrift.AppendEntryResult;
import org.apache.iotdb.consensus.raft.thrift.ElectionRequest;
import org.apache.iotdb.consensus.raft.thrift.ExecuteReq;
import org.apache.iotdb.consensus.raft.thrift.HeartBeatRequest;
import org.apache.iotdb.consensus.raft.thrift.HeartBeatResponse;
import org.apache.iotdb.consensus.raft.thrift.RaftService;
import org.apache.iotdb.consensus.raft.thrift.RequestCommitIndexResponse;
import org.apache.iotdb.consensus.raft.thrift.SendSnapshotRequest;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaftRPCServiceProcessor implements RaftService.AsyncIface {

  private final Logger logger = LoggerFactory.getLogger(RaftRPCServiceProcessor.class);

  private final RaftConsensus consensus;

  public RaftRPCServiceProcessor(RaftConsensus consensus) {
    this.consensus = consensus;
  }

  public void handleClientExit() {}

  @Override
  public void sendHeartbeat(
      HeartBeatRequest request, AsyncMethodCallback<HeartBeatResponse> resultHandler) {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(request.groupId));
    resultHandler.onComplete(member.processHeartbeatRequest(request));
  }

  @Override
  public void startElection(ElectionRequest request, AsyncMethodCallback<Long> resultHandler) {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(request.groupId));
    resultHandler.onComplete(member.processElectionRequest(request));
  }

  @Override
  public void appendEntries(
      AppendEntriesRequest request, AsyncMethodCallback<AppendEntryResult> resultHandler)
      throws TException {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(request.groupId));
    try {
      resultHandler.onComplete(member.appendEntries(request));
    } catch (UnknownLogTypeException e) {
      throw new TException(e);
    }
  }

  @Override
  public void sendSnapshot(SendSnapshotRequest request, AsyncMethodCallback<Void> resultHandler) {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(request.groupId));
    member.installSnapshot(request.snapshotBytes);
    resultHandler.onComplete(null);
  }

  @Override
  public void matchTerm(
      long index,
      long term,
      TConsensusGroupId groupId,
      AsyncMethodCallback<Boolean> resultHandler) {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(groupId));
    resultHandler.onComplete(member.matchLog(index, term));
  }

  @Override
  public void executeRequest(ExecuteReq request, AsyncMethodCallback<TSStatus> resultHandler) {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(request.groupId));
    resultHandler.onComplete(
        member
            .executeForwardedRequest(new ByteBufferConsensusRequest(request.requestBytes))
            .getStatus());
  }

  @Override
  public void ping(AsyncMethodCallback<Void> resultHandler) {
    resultHandler.onComplete(null);
  }

  @Override
  public void requestCommitIndex(
      TConsensusGroupId groupId, AsyncMethodCallback<RequestCommitIndexResponse> resultHandler) {
    RaftMember member = consensus.getMember(Factory.createFromTConsensusGroupId(groupId));
    resultHandler.onComplete(member.requestCommitIndex());
  }
}