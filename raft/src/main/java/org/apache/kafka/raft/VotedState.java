/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.raft;

import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The "voted" state is for voters who have cast their vote for a specific candidate.
 * Once a vote has been cast, it is not possible for a voter to change its vote until a
 * new election is started. If the election timeout expires before a new leader is elected,
 * then the voter will become a candidate.
 */
public class VotedState implements EpochState {
    // 当前角色投票的纪元
    private final int epoch;
    // 给哪个节点 ID 投票
    private final int votedId;
    // 投票者集合
    private final Set<Integer> voters;
    // 选举超时时间
    private final int electionTimeoutMs;
    // 选举定时器
    private final Timer electionTimer;
    private final Optional<LogOffsetMetadata> highWatermark;
    private final Logger log;

    public VotedState(
        Time time,
        int epoch,
        int votedId,
        Set<Integer> voters,
        Optional<LogOffsetMetadata> highWatermark,
        int electionTimeoutMs,
        LogContext logContext
    ) {
        this.epoch = epoch;
        this.votedId = votedId;
        this.voters = voters;
        this.highWatermark = highWatermark;
        this.electionTimeoutMs = electionTimeoutMs;
        this.electionTimer = time.timer(electionTimeoutMs);
        this.log = logContext.logger(VotedState.class);
    }

    @Override
    public ElectionState election() {
        return new ElectionState(
            epoch,
            OptionalInt.empty(),
            OptionalInt.of(votedId),
            voters
        );
    }

    public int votedId() {
        return votedId;
    }

    @Override
    public int epoch() {
        return epoch;
    }

    @Override
    public String name() {
        return "Voted";
    }

    public long remainingElectionTimeMs(long currentTimeMs) {
        electionTimer.update(currentTimeMs);
        return electionTimer.remainingMs();
    }

    public boolean hasElectionTimeoutExpired(long currentTimeMs) {
        electionTimer.update(currentTimeMs);
        return electionTimer.isExpired();
    }

    public void overrideElectionTimeout(long currentTimeMs, long timeoutMs) {
        electionTimer.update(currentTimeMs);
        electionTimer.reset(timeoutMs);
    }

    @Override
    public boolean canGrantVote(int candidateId, boolean isLogUpToDate) {
        if (votedId() == candidateId) {
            return true;
        }

        log.debug("Rejecting vote request from candidate {} since we already have voted for " +
            "another candidate {} in epoch {}", candidateId, votedId(), epoch);
        return false;
    }

    @Override
    public Optional<LogOffsetMetadata> highWatermark() {
        return highWatermark;
    }

    @Override
    public String toString() {
        return "Voted(" +
            "epoch=" + epoch +
            ", votedId=" + votedId +
            ", voters=" + voters +
            ", electionTimeoutMs=" + electionTimeoutMs +
            ')';
    }

    @Override
    public void close() {}
}
