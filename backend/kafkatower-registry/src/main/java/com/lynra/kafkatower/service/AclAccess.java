package com.lynra.kafkatower.service;

import org.apache.kafka.common.acl.AclOperation;

final class AclAccess {

    private AclAccess() {
    }

    static boolean isReadLike(AclOperation operation) {
        return operation == AclOperation.READ || operation == AclOperation.ALL;
    }

    static boolean isWriteLike(AclOperation operation) {
        return operation == AclOperation.WRITE || operation == AclOperation.ALL;
    }
}
