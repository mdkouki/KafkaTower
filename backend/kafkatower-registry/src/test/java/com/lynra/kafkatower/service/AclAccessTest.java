package com.lynra.kafkatower.service;

import org.apache.kafka.common.acl.AclOperation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AclAccessTest {

    @Test
    void readAndAllAreReadLike() {
        assertThat(AclAccess.isReadLike(AclOperation.READ)).isTrue();
        assertThat(AclAccess.isReadLike(AclOperation.ALL)).isTrue();
    }

    @Test
    void otherOperationsAreNotReadLike() {
        assertThat(AclAccess.isReadLike(AclOperation.WRITE)).isFalse();
        assertThat(AclAccess.isReadLike(AclOperation.DESCRIBE)).isFalse();
    }

    @Test
    void writeAndAllAreWriteLike() {
        assertThat(AclAccess.isWriteLike(AclOperation.WRITE)).isTrue();
        assertThat(AclAccess.isWriteLike(AclOperation.ALL)).isTrue();
    }

    @Test
    void otherOperationsAreNotWriteLike() {
        assertThat(AclAccess.isWriteLike(AclOperation.READ)).isFalse();
        assertThat(AclAccess.isWriteLike(AclOperation.CREATE)).isFalse();
    }
}
