package com.wavefront.agent.handlers;

import java.util.EnumMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.RecyclableRateLimiter;
import com.wavefront.agent.data.EntityProperties;
import com.wavefront.agent.data.EntityPropertiesFactory;
import com.wavefront.common.Managed;
import com.wavefront.data.ReportableEntityType;
import com.yammer.metrics.core.Clock;
import com.yammer.metrics.stats.ExponentiallyDecayingSample;
import com.yammer.metrics.stats.Sample;

/**
 * Experimental: use automatic traffic shaping (set rate limiter based on recently received
 * per second rates, heavily biased towards last 5 minutes)
 *
 * @author vasily@wavefront.com.
 */
public class TrafficShapingRateLimitAdjuster extends TimerTask implements Managed {
  private static final Logger log =
      Logger.getLogger(TrafficShapingRateLimitAdjuster.class.getCanonicalName());
  private static final int DEFAULT_SAMPLE_SIZE = 1028;
  private static final double DEFAULT_ALPHA = 0.015;
  private static final int MIN_RATE_LIMIT = 10; // 10 pps
  private static final double TOLERANCE_PERCENT = 5.0;

  private final EntityPropertiesFactory entityProps;
  private final double quantile;
  private final double headroom;
  private final Map<ReportableEntityType, Sample> perEntityStats =
      new EnumMap<>(ReportableEntityType.class);
  private final Map<ReportableEntityType, AtomicLong> perEntitySamples =
      new EnumMap<>(ReportableEntityType.class);
  private final Timer timer;
  private final Clock clock;

  /**
   * @param entityProps       entity properties factory
   * @param quantile          quantile of point rate to use
   * @param headroom          headroom multiplier and minimum headroom requirement
   */
  public TrafficShapingRateLimitAdjuster(EntityPropertiesFactory entityProps, double quantile,
                                         double headroom) {
    this(entityProps, quantile, headroom, Clock.defaultClock());
  }

  /**
   * @param entityProps       entity properties factory (to control rate limiters)
   * @param quantile          quantile of point rate to use
   * @param headroom          headroom multiplier
   * @param clock             clock for the exponentially decaying reservoir
   */
  @VisibleForTesting
  TrafficShapingRateLimitAdjuster(EntityPropertiesFactory entityProps, double quantile,
                                  double headroom, Clock clock) {
    Preconditions.checkArgument(headroom >= 1.0, "headroom can't be less than 1!");
    this.entityProps = entityProps;
    this.quantile = quantile > 1 ? quantile / 100 : quantile;
    this.headroom = headroom;
    this.clock = clock;
    this.timer = new Timer("traffic-shaping-adjuster-timer");
  }

  @Override
  public void run() {
    for (ReportableEntityType type : ReportableEntityType.values()) {
      AtomicLong samples = perEntitySamples.computeIfAbsent(type, k -> new AtomicLong(0));
      EntityProperties props = entityProps.get(type);
      long rate = props.getTotalReceivedRate();
      boolean hasBacklog = props.getTotalBacklogSize() > 0;
      if (rate > 0 || samples.get() > 0) {
        samples.incrementAndGet();
        Sample stats = updateStats(type, rate);
        if (samples.get() >= 300) { // need at least 5 minutes worth of stats to enable the limiter
          RecyclableRateLimiter rateLimiter = props.getRateLimiter();
          adjustRateLimiter(type, stats, rateLimiter, hasBacklog);
        }
      }
    }
  }

  @Override
  public void start() {
    timer.scheduleAtFixedRate(this, 1000, 1000);
  }

  @Override
  public void stop() {
    timer.cancel();
  }

  @VisibleForTesting
  Sample updateStats(ReportableEntityType type, long rate) {
    Sample sample = perEntityStats.computeIfAbsent(type, x ->
        new ExponentiallyDecayingSample(DEFAULT_SAMPLE_SIZE, DEFAULT_ALPHA, clock));
    sample.update(rate);
    return sample;
  }

  @VisibleForTesting
  void adjustRateLimiter(ReportableEntityType type, Sample sample,
                         RecyclableRateLimiter rateLimiter, boolean backlog) {
    double suggestedLimit = (MIN_RATE_LIMIT + sample.getSnapshot().getValue(quantile)) *
        (backlog ? headroom : 1.0);
    double currentRate = rateLimiter.getRate();
    if (Math.abs(currentRate - suggestedLimit) > currentRate * TOLERANCE_PERCENT / 100) {
      log.fine("Setting rate limit for " + type.toString() + " to " + suggestedLimit);
      rateLimiter.setRate(suggestedLimit);
    }
  }
}
