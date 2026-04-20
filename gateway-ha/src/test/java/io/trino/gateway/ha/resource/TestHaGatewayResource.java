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
package io.trino.gateway.ha.resource;

import io.airlift.json.JsonCodec;
import io.trino.gateway.ha.HaGatewayLauncher;
import io.trino.gateway.ha.HaGatewayTestUtils;
import io.trino.gateway.ha.clustermonitor.ClusterStats;
import io.trino.gateway.ha.clustermonitor.TrinoStatus;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.File;
import java.io.IOException;

import static io.trino.gateway.ha.util.TestcontainersUtils.createPostgreSqlContainer;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(Lifecycle.PER_CLASS)
final class TestHaGatewayResource
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final PostgreSQLContainer postgresql = createPostgreSqlContainer();
    private final OkHttpClient httpClient = new OkHttpClient();
    final int routerPort = 23001 + (int) (Math.random() * 1000);

    @BeforeAll
    void setup()
            throws Exception
    {
        postgresql.start();
        File configFile = HaGatewayTestUtils.buildGatewayConfig(
                postgresql, routerPort, "test-config-no-monitor-template.yml");
        HaGatewayLauncher.main(new String[] {configFile.getAbsolutePath()});
        addBackend("update-to-active-test", false);
        addBackend("update-to-inactive-test", true);
        addBackend("remove-test", true);
    }

    @Test
    void testAddActiveBackendSetsPendingInHealthCache()
            throws IOException
    {
        addBackend("add-active-test", true);

        assertThat(getBackendState("add-active-test").trinoStatus()).isEqualTo(TrinoStatus.PENDING);
    }

    @Test
    void testAddInactiveBackendSetsUnhealthyInHealthCache()
            throws IOException
    {
        addBackend("add-inactive-test", false);

        assertThat(getBackendState("add-inactive-test").trinoStatus()).isEqualTo(TrinoStatus.UNHEALTHY);
    }

    @Test
    void testUpdateActiveBackendSetsPendingInHealthCache()
            throws IOException
    {
        String body = format(
                "{\"name\":\"%s\",\"proxyTo\":\"http://localhost:9999\","
                        + "\"externalUrl\":\"http://localhost:9999\","
                        + "\"routingGroup\":\"adhoc\",\"active\":true}",
                "update-to-active-test");
        Request request = new Request.Builder()
                .url(format("http://localhost:%s/gateway/backend/modify/update", routerPort))
                .post(RequestBody.create(body, JSON))
                .build();
        assertThat(httpClient.newCall(request).execute().isSuccessful()).isTrue();

        assertThat(getBackendState("update-to-active-test").trinoStatus()).isEqualTo(TrinoStatus.PENDING);
    }

    @Test
    void testUpdateInactiveBackendSetsUnhealthyInHealthCache()
            throws IOException
    {
        String body = format(
                "{\"name\":\"%s\",\"proxyTo\":\"http://localhost:9999\","
                        + "\"externalUrl\":\"http://localhost:9999\","
                        + "\"routingGroup\":\"adhoc\",\"active\":false}",
                "update-to-inactive-test");
        Request request = new Request.Builder()
                .url(format("http://localhost:%s/gateway/backend/modify/update", routerPort))
                .post(RequestBody.create(body, JSON))
                .build();
        assertThat(httpClient.newCall(request).execute().isSuccessful()).isTrue();

        assertThat(getBackendState("update-to-inactive-test").trinoStatus()).isEqualTo(TrinoStatus.UNHEALTHY);
    }

    @Test
    void testRemoveBackendCleansUpState()
            throws IOException
    {
        assertThat(getBackendState("remove-test").trinoStatus()).isEqualTo(TrinoStatus.PENDING);

        Request request = new Request.Builder()
                .url(format("http://localhost:%s/gateway/backend/modify/delete", routerPort))
                .post(RequestBody.create("remove-test", JSON))
                .build();
        assertThat(httpClient.newCall(request).execute().isSuccessful()).isTrue();

        Request backendRequest = new Request.Builder()
                .url(format("http://localhost:%s/api/public/backends/remove-test", routerPort))
                .get()
                .build();
        assertThat(httpClient.newCall(backendRequest).execute().code()).isEqualTo(404);

        Request allBackendsRequest = new Request.Builder()
                .url(format("http://localhost:%s/gateway/backend/all", routerPort))
                .get()
                .build();
        Response allBackendsResponse = httpClient.newCall(allBackendsRequest).execute();
        assertThat(allBackendsResponse.body().string()).doesNotContain("remove-test");

        Request stateRequest = new Request.Builder()
                .url(format("http://localhost:%s/api/public/backends/remove-test/state", routerPort))
                .get()
                .build();
        assertThat(httpClient.newCall(stateRequest).execute().code()).isEqualTo(404);
    }

    private void addBackend(String name, boolean active)
            throws IOException
    {
        String body = format(
                "{\"name\":\"%s\",\"proxyTo\":\"http://localhost:9999\","
                        + "\"externalUrl\":\"http://localhost:9999\","
                        + "\"routingGroup\":\"adhoc\",\"active\":%s}",
                name,
                active);
        Request request = new Request.Builder()
                .url(format("http://localhost:%s/gateway/backend/modify/add", routerPort))
                .post(RequestBody.create(body, JSON))
                .build();
        assertThat(httpClient.newCall(request).execute().isSuccessful()).isTrue();
    }

    private ClusterStats getBackendState(String name)
            throws IOException
    {
        Request request = new Request.Builder()
                .url(format("http://localhost:%s/api/public/backends/%s/state", routerPort, name))
                .get()
                .build();
        Response response = httpClient.newCall(request).execute();
        return JsonCodec.jsonCodec(ClusterStats.class).fromJson(response.body().string());
    }
}
