/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.http.prometheus;

import ai.vespa.metricsproxy.http.HttpHandlerTestBase;
import ai.vespa.metricsproxy.service.DummyService;
import com.yahoo.container.jdisc.RequestHandlerTestDriver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author gjoranv
 */
@SuppressWarnings("UnstableApiUsage")
public class PrometheusHandlerTest extends HttpHandlerTestBase {

    private static final String V1_URI = URI_BASE + PrometheusHandler.V1_PATH;
    private static final String VALUES_URI = URI_BASE + PrometheusHandler.VALUES_PATH;

    private static String valuesResponse;

    @BeforeClass
    public static void setup() {
        PrometheusHandler handler = new PrometheusHandler(Executors.newSingleThreadExecutor(),
                                                          getMetricsManager(),
                                                          vespaServices,
                                                          getMetricsConsumers());
        testDriver = new RequestHandlerTestDriver(handler);
        valuesResponse = testDriver.sendRequest(VALUES_URI).readAll();
    }

    @Test
    public void v1_response_contains_values_uri() throws Exception {
        String response = testDriver.sendRequest(V1_URI).readAll();
        JSONObject root = new JSONObject(response);
        assertTrue(root.has("resources"));

        JSONArray resources = root.getJSONArray("resources");
        assertEquals(1, resources.length());

        JSONObject valuesUrl = resources.getJSONObject(0);
        assertEquals(VALUES_URI, valuesUrl.getString("url"));
    }

    @Ignore
    @Test
    public void visually_inspect_values_response() {
        System.out.println(valuesResponse);
    }

    @Test
    public void response_contains_node_status() {
        assertTrue(valuesResponse.contains("vespa_node_status 1.0"));
    }

    @Test
    public void response_contains_node_metrics() {
        String cpu = getLine(valuesResponse, CPU_METRIC + "{");
        assertTrue(cpu.contains("} 12.345"));   // metric value
        assertTrue(cpu.contains("{vespaVersion="));
    }

    @Test
    public void response_contains_service_status() {
        assertTrue(valuesResponse.contains("vespa_dummy_status 1.0"));
        assertTrue(valuesResponse.contains("vespa_down_service_status 0.0"));
    }

    @Test
    public void response_contains_service_metrics() {
        String dummy0 = getLine(valuesResponse, DummyService.NAME + "0");
        assertTrue(dummy0.contains("c_test"));  // metric name
        assertTrue(dummy0.contains("} 1.0"));   // metric value
    }

    @Test
    public void service_metrics_have_configured_dimensions() {
        String dummy0 = getLine(valuesResponse, DummyService.NAME + "0");
        assertTrue(dummy0.contains("consumer_dim=\"default-val\""));
    }

    @Test
    public void service_metrics_have_vespa_service_dimension() {
        String dummy0 = getLine(valuesResponse, DummyService.NAME + "0");
        assertTrue(dummy0.contains("vespa_service=\"vespa_dummy\""));
    }

    // Find the first line that contains the given string
    private String getLine(String raw, String searchString) {
        for (var s : raw.split("\\n")) {
            if (s.contains(searchString))
                return s;
        }
        throw new IllegalArgumentException("No line containing string: " + searchString);
    }
}
