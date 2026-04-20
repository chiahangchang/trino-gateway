/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.router;

import io.trino.gateway.ha.clustermonitor.ClusterStats;
import io.trino.gateway.ha.clustermonitor.TrinoStatus;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.weakref.jmx.MBeanExporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

final class TestBackendStateManager
{
    private MBeanExporter exporter;
    private BackendStateManager backendStateManager;

    @BeforeEach
    void setUp()
    {
        exporter = MBeanExporter.withPlatformMBeanServer();
        backendStateManager = new BackendStateManager(exporter);
    }

    @AfterEach
    void tearDown()
    {
        exporter.unexportAll();
    }

    @Test
    void testRemoveStatesUnexportsJmxBean()
    {
        String clusterId = "test-cluster";
        ClusterStats stats = ClusterStats.builder(clusterId)
                .trinoStatus(TrinoStatus.HEALTHY)
                .runningQueryCount(5)
                .queuedQueryCount(2)
                .numWorkerNodes(10)
                .build();

        backendStateManager.updateStates(clusterId, stats);

        assertThat(exporter.getExportedObjects()).hasSize(1);

        ProxyBackendConfiguration backend = new ProxyBackendConfiguration();
        backend.setName(clusterId);
        assertThat(backendStateManager.getBackendState(backend).trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);

        backendStateManager.removeStates(clusterId);

        assertThat(exporter.getExportedObjects()).isEmpty();
        assertThat(backendStateManager.getBackendState(backend).trinoStatus()).isEqualTo(TrinoStatus.UNKNOWN);
    }

    @Test
    void testRemoveStatesForNonexistentClusterDoesNotThrow()
    {
        assertThatNoException().isThrownBy(() -> backendStateManager.removeStates("nonexistent"));
    }
}
