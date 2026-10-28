package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.PrincipalDependencies;
import com.lynra.kafkatower.model.PrincipalDependencies.Related;
import com.lynra.kafkatower.model.UserAclSummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Derives topic-based producer/consumer relationships between principals from the cached ACL graph.
 * ACL topic names may be PREFIXED (trailing {@code *}), so two entries "share" a topic when they are
 * equal, or when one is a prefix pattern covering the other (or both are prefix patterns that overlap).
 */
public final class PrincipalDependencyBuilder {

    private PrincipalDependencyBuilder() {
    }

    public static PrincipalDependencies build(ClusterAclGraph graph, String principal) {
        UserAclSummary self = graph.usersByPrincipal().get(principal);
        if (self == null) {
            return null;
        }
        Map<String, TreeSet<String>> upstream = new TreeMap<>();
        Map<String, TreeSet<String>> downstream = new TreeMap<>();

        for (UserAclSummary other : graph.usersByPrincipal().values()) {
            if (other.principal().equals(principal)) continue;
            collect(other.produceTopics(), self.consumeTopics(), other.principal(), upstream);
            collect(other.consumeTopics(), self.produceTopics(), other.principal(), downstream);
        }
        return new PrincipalDependencies(principal, toRelated(upstream), toRelated(downstream));
    }

    private static void collect(Collection<String> theirs, Collection<String> mine, String other,
                                Map<String, TreeSet<String>> out) {
        for (String t : theirs) {
            for (String m : mine) {
                if (overlaps(t, m)) {
                    TreeSet<String> shared = out.computeIfAbsent(other, k -> new TreeSet<>());
                    // Report the more specific (literal) name when only one side is a pattern.
                    shared.add(isPrefix(t) && !isPrefix(m) ? m : t);
                }
            }
        }
    }

    /** True when some concrete topic name could match both ACL names. */
    static boolean overlaps(String a, String b) {
        boolean pa = isPrefix(a), pb = isPrefix(b);
        if (!pa && !pb) return a.equals(b);
        if (pa && !pb) return b.startsWith(stem(a));
        if (!pa) return a.startsWith(stem(b));
        return stem(a).startsWith(stem(b)) || stem(b).startsWith(stem(a));
    }

    private static boolean isPrefix(String n) {
        return n.endsWith("*");
    }

    private static String stem(String n) {
        return n.substring(0, n.length() - 1);
    }

    private static List<Related> toRelated(Map<String, TreeSet<String>> m) {
        return m.entrySet().stream().map(e -> new Related(e.getKey(), List.copyOf(e.getValue()))).toList();
    }
}
