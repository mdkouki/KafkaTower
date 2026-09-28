package com.lynra.kafkatower.kafka;

import java.util.List;

public record GroupInfo(
        String groupId,
        String state,
        String coordinator,
        String assignor,
        List<MemberInfo> members,
        List<PartitionLag> partitionLags,
        List<String> neverCommittedPartitions,
        long totalLag
) {
    public record MemberInfo(
            String memberId,
            String clientId,
            String host,
            List<String> assignedPartitions
    ) {
    }

    public record PartitionLag(
            String topic,
            int partition,
            long committedOffset,
            long endOffset,
            long lag
    ) {
        /** Committed offset is ahead of log end — sign of a truncated or recreated topic. */
        public boolean negativeLag() {
            return lag < 0;
        }
    }
}
