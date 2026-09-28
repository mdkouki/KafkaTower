package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupLookupException;
import com.lynra.kafkatower.kafka.GroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/{group}")
    public ResponseEntity<GroupInfo> getGroupState(
            @PathVariable("group") String group,
            @RequestParam("clusterName") String clusterName) {
        try {
            GroupInfo info = groupService.getGroupState(group, clusterName);
            if (info == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(info);
        } catch (GroupLookupException e) {
            return ResponseEntity.status(503).build();
        }
    }
}