package io.dropwizard.metrics5;

import com.google.common.annotations.VisibleForTesting;
import com.wavefront.common.MetricConstants;

/**
 * A counter for Wavefront delta metrics.
 *
 * Differs from a counter in that it is reset in the WavefrontReporter every time the value is reported.
 *
 * @author Vikram Raman (vikram@wavefront.com)
 */
public class DeltaCounter extends Counter {

  @VisibleForTesting
  public static synchronized DeltaCounter get(MetricRegistry registry, MetricName metricName) {

    if (registry == null || metricName == null || metricName.getKey().isEmpty()) {
      throw new IllegalArgumentException("Invalid arguments");
    }

    if (!(metricName.getKey().startsWith(MetricConstants.DELTA_PREFIX) ||
        metricName.getKey().startsWith(MetricConstants.DELTA_PREFIX_2))) {
      metricName = new MetricName(MetricConstants.DELTA_PREFIX + metricName.getKey(),
          metricName.getTags());
    }
    DeltaCounter counter = new DeltaCounter();
    try {
      return registry.register(metricName, counter);
    } catch(IllegalArgumentException e) {
      Counter existing = registry.counter(metricName);
      if (existing instanceof DeltaCounter) {
        return (DeltaCounter) existing;
      } else {
        throw new IllegalStateException("Found existing non-DeltaCounter: " + existing);
      }
    }
  }
}