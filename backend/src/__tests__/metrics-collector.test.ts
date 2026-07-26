import { describe, it, expect } from 'vitest';
import { MetricsCollector } from '../common/metrics-collector.js';

describe('MetricsCollector', () => {
  it('starts with empty snapshot', () => {
    const mc = new MetricsCollector();
    const snap = mc.snapshot();
    expect(snap.counters).toEqual({});
    expect(snap.gauges).toEqual({});
    expect(snap.timings).toEqual({});
    expect(snap.uptime).toBeGreaterThanOrEqual(0);
  });

  it('increments counters', () => {
    const mc = new MetricsCollector();
    mc.incrementCounter('test.counter');
    expect(mc.snapshot().counters['test.counter']).toBe(1);
    mc.incrementCounter('test.counter', 3);
    expect(mc.snapshot().counters['test.counter']).toBe(4);
  });

  it('sets gauges', () => {
    const mc = new MetricsCollector();
    mc.setGauge('test.gauge', 42);
    expect(mc.snapshot().gauges['test.gauge']).toBe(42);
    mc.setGauge('test.gauge', 7);
    expect(mc.snapshot().gauges['test.gauge']).toBe(7);
  });

  it('records and summarizes timings', () => {
    const mc = new MetricsCollector();
    mc.recordTiming('test.op', 10);
    mc.recordTiming('test.op', 20);
    mc.recordTiming('test.op', 30);

    const timings = mc.snapshot().timings['test.op'];
    expect(timings.count).toBe(3);
    expect(timings.min).toBe(10);
    expect(timings.max).toBe(30);
    expect(timings.avg).toBe(20);
  });

  it('limits timing samples', () => {
    const mc = new MetricsCollector();
    for (let i = 0; i < 2000; i++) {
      mc.recordTiming('big', i);
    }
    const snap = mc.snapshot();
    expect(snap.timings['big'].count).toBeLessThanOrEqual(1000);
  });
});
