package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.PrincipalDependencies;
import com.lynra.kafkatower.model.UserAclSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrincipalDependencyBuilderTest {

    private static UserAclSummary user(String p, List<String> consume, List<String> produce) {
        return new UserAclSummary(p, consume, produce, List.of(), List.of());
    }

    private static ClusterAclGraph graph(UserAclSummary... users) {
        Map<String, UserAclSummary> m = new java.util.TreeMap<>();
        for (UserAclSummary u : users) m.put(u.principal(), u);
        return new ClusterAclGraph("prod", m, Instant.now(), null);
    }

    @Test
    void findsUpstreamAndDownstreamViaLiteralTopics() {
        var g = graph(
                user("User:a", List.of("in"), List.of("mid")),
                user("User:up", List.of(), List.of("in")),
                user("User:down", List.of("mid"), List.of()),
                user("User:unrelated", List.of("x"), List.of("y")));

        PrincipalDependencies d = PrincipalDependencyBuilder.build(g, "User:a");

        assertEquals(1, d.upstream().size());
        assertEquals("User:up", d.upstream().get(0).principal());
        assertEquals(List.of("in"), d.upstream().get(0).topics());
        assertEquals(1, d.downstream().size());
        assertEquals("User:down", d.downstream().get(0).principal());
        assertEquals(List.of("mid"), d.downstream().get(0).topics());
    }

    @Test
    void matchesPrefixedAclsAgainstLiteralTopics() {
        var g = graph(
                user("User:a", List.of(), List.of("orders.created")),
                user("User:down", List.of("orders.*"), List.of()));

        PrincipalDependencies d = PrincipalDependencyBuilder.build(g, "User:a");

        assertEquals(List.of("orders.created"), d.downstream().get(0).topics());
    }

    @Test
    void overlappingPrefixesMatchButDisjointOnesDoNot() {
        assertTrue(PrincipalDependencyBuilder.overlaps("orders.*", "orders.eu.*"));
        assertTrue(PrincipalDependencyBuilder.overlaps("orders.eu.*", "orders.*"));
        assertFalse(PrincipalDependencyBuilder.overlaps("orders.*", "billing.*"));
        assertFalse(PrincipalDependencyBuilder.overlaps("orders", "orders2"));
    }

    @Test
    void principalCanBeBothUpstreamAndDownstreamAndNeverItself() {
        var g = graph(
                user("User:a", List.of("t"), List.of("t")),
                user("User:b", List.of("t"), List.of("t")));

        PrincipalDependencies d = PrincipalDependencyBuilder.build(g, "User:a");

        assertEquals(1, d.upstream().size());
        assertEquals(1, d.downstream().size());
    }

    @Test
    void unknownPrincipalReturnsNull() {
        assertNull(PrincipalDependencyBuilder.build(graph(), "User:ghost"));
    }
}
