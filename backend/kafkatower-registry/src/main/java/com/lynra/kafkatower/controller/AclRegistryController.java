package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.AclClusterStatus;
import com.lynra.kafkatower.model.AclUserSummaryLite;
import com.lynra.kafkatower.model.UserAclSummary;
import com.lynra.kafkatower.service.AclGraphStore;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Serves the in-memory ACL graph built by {@code AclGraphRefreshJob} — read-only, no live Kafka calls. */
@RestController
@RequestMapping("/api/registry")
public class AclRegistryController {

    private final AclGraphStore store;
    private final Map<String, AdminClient> kafkaAdminMap;
    private final GroupService groupService;

    public AclRegistryController(AclGraphStore store, Map<String, AdminClient> kafkaAdminMap, GroupService groupService) {
        this.store = store;
        this.kafkaAdminMap = kafkaAdminMap;
        this.groupService = groupService;
    }

    /** Every live, configured Kafka cluster ({@code kafka.clusters.*}) — the sole cluster source now that KADYC import is gone. */
    @GetMapping("/clusters")
    public List<String> listClusters() {
        return kafkaAdminMap.keySet().stream().sorted().toList();
    }

    @GetMapping("/acl-users")
    public ResponseEntity<List<AclUserSummaryLite>> listUsers(@RequestParam String clusterName) {
        return store.get(clusterName)
                .map(g -> ResponseEntity.ok(
                        g.usersByPrincipal().values().stream()
                                .map(AclUserSummaryLite::from)
                                .sorted(Comparator.comparing(AclUserSummaryLite::principal))
                                .toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/acl-users/detail")
    public ResponseEntity<UserAclSummary> getUser(@RequestParam String clusterName, @RequestParam String principal) {
        return store.get(clusterName)
                .flatMap(g -> Optional.ofNullable(g.usersByPrincipal().get(principal)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/acl-users/status")
    public ResponseEntity<AclClusterStatus> status(@RequestParam String clusterName) {
        return store.get(clusterName)
                .map(g -> ResponseEntity.ok(new AclClusterStatus(g.clusterName(), g.builtAt(), g.usersByPrincipal().size(), g.error())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The real, currently-existing consumer groups covered by one ACL group grant — LITERAL
     * matches the exact group ID, PREFIXED matches every group ID starting with it. Live, not
     * served from the cached ACL graph: an ACL grant can exist long before any group using it
     * does, or the group can come and go, so this always reflects what's on the broker right now.
     */
    @GetMapping("/acl-users/detail/groups")
    public List<GroupService.GroupSummary> groupsForGrant(
            @RequestParam String clusterName,
            @RequestParam String pattern,
            @RequestParam(defaultValue = "false") boolean prefixed) {
        return groupService.listGroupsWithState(clusterName).stream()
                .filter(g -> prefixed ? g.groupId().startsWith(pattern) : g.groupId().equals(pattern))
                .sorted(Comparator.comparing(GroupService.GroupSummary::groupId))
                .toList();
    }
}
