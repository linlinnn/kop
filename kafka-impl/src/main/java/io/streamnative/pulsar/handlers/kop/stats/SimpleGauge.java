/**
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
package io.streamnative.pulsar.handlers.kop.stats;

import org.apache.bookkeeper.stats.Gauge;

/**
 * A {@link Gauge} implementation that forwards on the value supplier.
 */
public class SimpleGauge<T extends Number> {

    // public SimpleGauge(CollectorRegistry registry, String name) {
    // this.gauge = PrometheusUtil.safeRegister(registry,
    // Gauge.build().name(Collector.sanitizeMetricName(name)).help("-").create());
    // }

    private final Gauge<T> gauge;

    public SimpleGauge(final Gauge<T> gauge) {
        this.gauge = gauge;
    }

    public Number getSample() {
        return gauge.getSample();
    }
}
