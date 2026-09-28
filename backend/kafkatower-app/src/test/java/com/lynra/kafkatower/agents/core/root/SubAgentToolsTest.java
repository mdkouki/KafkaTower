package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.core.AgentPlatform;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SubAgentToolsTest {

    @Mock
    private AgentPlatform agentPlatform;

    @Mock
    private AdminClient prodAdminClient;

    @Mock
    private AdminClient stagingAdminClient;

    private SubAgentTools tools;

    @BeforeEach
    void setUp() {
        Map<String, AdminClient> kafkaAdminMap = Map.of("prod", prodAdminClient, "staging", stagingAdminClient);

        tools = new SubAgentTools(agentPlatform, kafkaAdminMap, List.of());
    }

    @Test
    void listClustersReportsEveryLiveCluster() {
        String output = tools.listClusters("");

        assertThat(output).contains("'staging'");
        assertThat(output).contains("'prod'");
    }

    @Test
    void listClustersRanksExactMatchAheadOfUnrelatedNames() {
        String output = tools.listClusters("prod");

        int prodIndex = output.indexOf("'prod'");
        int stagingIndex = output.indexOf("'staging'");
        assertThat(prodIndex).isGreaterThan(-1);
        assertThat(stagingIndex).isGreaterThan(-1);
        assertThat(prodIndex).isLessThan(stagingIndex);
    }

    @Test
    void listClustersResolvesTyposViaEditDistance() {
        String output = tools.listClusters("stagng"); // missing the 'i' — not a substring of either name

        assertThat(output.indexOf("'staging'")).isLessThan(output.indexOf("'prod'"));
    }

    @Test
    void listClustersReportsNoneWhenNoClustersConfigured() {
        SubAgentTools empty = new SubAgentTools(agentPlatform, Map.of(), List.of());

        String output = empty.listClusters("");

        assertThat(output).contains("No Kafka clusters are configured");
    }
}
