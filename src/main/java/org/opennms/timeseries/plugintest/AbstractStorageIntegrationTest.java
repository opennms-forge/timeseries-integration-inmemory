/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2006-2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.timeseries.plugintest;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.timeseries.Aggregation;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Sample;
import org.opennms.integration.api.v1.timeseries.StorageException;
import org.opennms.integration.api.v1.timeseries.TimeSeriesFetchRequest;
import org.opennms.integration.api.v1.timeseries.TimeSeriesStorage;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableSample;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableTag;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableTimeSeriesFetchRequest;

// Extend this class in order to create an integration test for your olugin.
public abstract class AbstractStorageIntegrationTest {

    protected List<Metric> metrics;
    protected List<Sample> samplesOfFirstMetric;
    protected TimeSeriesStorage storage;

    @Before
    public void setUp() throws StorageException {
        metrics = createRandomMetrics();
        List<Sample> samples = metrics.stream()
                .map(AbstractStorageIntegrationTest::createSamplesForMetric)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        this.storage = createStorage();
        storage.store(samples);
        samplesOfFirstMetric = samples.stream().filter(s -> s.getMetric().equals(metrics.get(0))).collect(Collectors.toList());
        waitForPersistingChanges(); // make sure the samples are stored before we test
    }

    abstract protected TimeSeriesStorage createStorage();

    protected void waitForPersistingChanges() {
        // override and add a wait in case persisting is not handled synchronously
    }

    @Test
    public void shouldLoadMultipleMetricsWithSameTag() throws StorageException {
        List<Metric> metricsRetrieved = storage.getMetrics(asList(
                metrics.get(0).getFirstTagByKey("name"),
                new ImmutableTag("_idx1", "(snmp:1,4)")));
        assertEquals(metrics.size(), metricsRetrieved.size());
        assertEquals(new HashSet<>(metrics), new HashSet<>(metricsRetrieved));
    }

    @Test
    public void shouldLoadOneMetricsWithUniqueTag() throws StorageException {
        Metric metric = metrics.get(0);
        List<Metric> metricsRetrieved = storage.getMetrics(asList(
                metric.getFirstTagByKey("name"),
                metric.getFirstTagByKey("resourceId")));
        assertEquals(1, metricsRetrieved.size());

        Metric metricFromDb = metricsRetrieved.get(0);
        assertEquals(metric, metricFromDb);

        // metrics are unique by its intrinsic tags => we still need to check if all meta tags were received as well
        assertEquals(metric.getMetaTags(), metricFromDb.getMetaTags());
    }

    @Test
    public void shouldLoadMetricsByWildcardTag() throws StorageException {
        List<Metric> metricsRetrieved = storage.getMetrics(asList(
                metrics.get(0).getFirstTagByKey("name"),
                new ImmutableTag("_idx2w", "(snmp:1,*)")));
        assertEquals(metrics.size(), metricsRetrieved.size());
        assertEquals(new HashSet<>(metrics), new HashSet<>(metricsRetrieved));
    }

    @Test
    public void shouldGetSamplesForMetric() throws StorageException {

        // let's create a metric without meta tags (they are not relevant for a metric definition)
        ImmutableMetric.MetricBuilder builder = ImmutableMetric.builder();
        metrics.get(0).getIntrinsicTags().forEach(builder::intrinsicTag);
        Metric metric = builder.build();

        // query for the samples
        List<Sample> samples = loadSamplesForMetric(metric);
        assertEquals(samplesOfFirstMetric, samples);

        // check if the retrieved metric has all the meta tags that we stored.
        for(int i = 0; i < samplesOfFirstMetric.size(); i++) {
            assertEquals(samplesOfFirstMetric.get(i).getMetric().getMetaTags(), samples.get(i).getMetric().getMetaTags());
        }
    }

    @Test
    public void shouldDeleteMetrics() throws StorageException {
        Metric lastMetric = metrics.get(metrics.size()-1);

        // make sure we have the metrics and the samples in the db:
        List<Metric> metricsRetrieved = storage.getMetrics(singletonList(this.metrics.get(0).getFirstTagByKey("name")));
        assertEquals(new HashSet<>(metrics), new HashSet<>(metricsRetrieved));
        List<Sample> samples = loadSamplesForMetric(lastMetric);
        assertEquals(samplesOfFirstMetric.size(), samples.size());

        // let's delete the last one
        storage.delete(lastMetric);

        // check again, first metric should be gone
        metricsRetrieved = storage.getMetrics(lastMetric.getIntrinsicTags());
        assertTrue(metricsRetrieved.isEmpty());
        samples = loadSamplesForMetric(lastMetric);
        assertEquals(0, samples.size());

        // check the rest of metrics, they should still be there
        metricsRetrieved = storage.getMetrics(singletonList(this.metrics.get(0).getFirstTagByKey("name")));
        assertEquals(new HashSet<>(metrics.subList(0, metrics.size()-1)), new HashSet<>(metricsRetrieved));
        samples = loadSamplesForMetric(metrics.get(0));
        assertEquals(samplesOfFirstMetric, samples);
    }

    private List<Sample> loadSamplesForMetric(final Metric metric) throws StorageException {
        TimeSeriesFetchRequest request = ImmutableTimeSeriesFetchRequest.builder()
                .start(Instant.now().minusSeconds(300))
                .end(Instant.now())
                .metric(metric)
                .aggregation(Aggregation.NONE)
                .step(Duration.ZERO)
                .build();
        return storage.getTimeseries(request);

    }

    private static List<Sample> createSamplesForMetric(final Metric metric) {
        List<Sample> samples = new ArrayList<>();
        for(int i=1; i<=5; i++) {
            samples.add(createSampleForMetric(metric, i));
        }
        return samples;
    }

    private static Sample createSampleForMetric(final Metric metric, int index) {
        return ImmutableSample.builder()
                .time(Instant.now().with(ChronoField.MICRO_OF_SECOND, 0).plus(index, ChronoUnit.MILLIS)) // Influxdb doesn't have microseconds
                .value(42.3)
                .metric(metric)
                .build();
    }

    private static List<Metric> createRandomMetrics() {
        final String uuid = UUID.randomUUID().toString().replaceAll("-", "");
        List<Metric> metrics = new ArrayList<>();
        for(int i=1; i<5; i++) {
            metrics.add(createMetric(uuid, i));
        }
        return metrics;
    }

    private static Metric createMetric(final String uuid, final int nodeId) {
        return ImmutableMetric.builder()
                .intrinsicTag("name", "n" + uuid) // make sure the name starts with a letter and not a number
                .intrinsicTag("resourceId", String.format("snmp:%s:opennms-jvm:org_opennms_newts_name_ring_buffer_max_size_unit=unknown", nodeId))
                .metaTag("mtype", Metric.Mtype.gauge.name())
                .metaTag("_idx0", "(snmp,4)")
                .metaTag("_idx1", "(snmp:1,4)")
                .metaTag("_idx2w", "(snmp:1,*)")
                .metaTag("_idx2", "(snmp:1:opennms-jvm,4)")
                .metaTag("_idx3", "(snmp:1:opennms-jvm:OpenNMS_Name_Notifd,4)")
                .metaTag("host", "myHost" + nodeId)
                .build();
    }
}
