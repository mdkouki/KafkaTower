package com.lynra.kafkatower.model;

/**
 * One ACL grant of consumer-group access, as originally declared on the broker — LITERAL grants
 * a single exact group ID, PREFIXED grants every group ID starting with {@code pattern}.
 */
public record AclGroupGrant(String pattern, boolean prefixed) {
}
