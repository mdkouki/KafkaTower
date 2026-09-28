package com.lynra.kafkatower.model;

/** Row shape for the ACL user picker list — counts only, full detail is a separate call. */
public record AclUserSummaryLite(
        String principal,
        int consumeTopicCount,
        int produceTopicCount,
        int consumerGroupCount,
        int transactionalIdCount) {

    public static AclUserSummaryLite from(UserAclSummary s) {
        return new AclUserSummaryLite(
                s.principal(),
                s.consumeTopics().size(),
                s.produceTopics().size(),
                s.consumerGroups().size(),
                s.transactionalIds().size());
    }
}
