export interface MetricsSnapshot {
  counters: Record<string, number>;
  gauges: Record<string, number>;
  timings: Record<string, TimingSummary>;
  uptime: number;
  timestamp: string;
}

export interface TimingSummary {
  count: number;
  min: number;
  max: number;
  avg: number;
  p50: number;
  p95: number;
  p99: number;
}

export class MetricsCollector {
  private counters: Map<string, number> = new Map();
  private gauges: Map<string, number> = new Map();
  private timings: Map<string, number[]> = new Map();
  private maxTimingSamples = 1000;

  incrementCounter(name: string, delta: number = 1): void {
    const current = this.counters.get(name) ?? 0;
    this.counters.set(name, current + delta);
  }

  setGauge(name: string, value: number): void {
    this.gauges.set(name, value);
  }

  recordTiming(name: string, durationMs: number): void {
    let samples = this.timings.get(name);
    if (!samples) {
      samples = [];
      this.timings.set(name, samples);
    }
    samples.push(durationMs);
    if (samples.length > this.maxTimingSamples) {
      samples.splice(0, samples.length - this.maxTimingSamples);
    }
  }

  snapshot(): MetricsSnapshot {
    const counters: Record<string, number> = {};
    for (const [k, v] of this.counters) counters[k] = v;

    const gauges: Record<string, number> = {};
    for (const [k, v] of this.gauges) gauges[k] = v;

    const timings: Record<string, TimingSummary> = {};
    for (const [k, samples] of this.timings) {
      timings[k] = this.computeTimingSummary(samples);
    }

    return {
      counters,
      gauges,
      timings,
      uptime: process.uptime(),
      timestamp: new Date().toISOString(),
    };
  }

  private computeTimingSummary(samples: number[]): TimingSummary {
    if (samples.length === 0) {
      return { count: 0, min: 0, max: 0, avg: 0, p50: 0, p95: 0, p99: 0 };
    }
    const sorted = [...samples].sort((a, b) => a - b);
    const sum = sorted.reduce((a, b) => a + b, 0);
    const n = sorted.length;
    return {
      count: n,
      min: sorted[0] ?? 0,
      max: sorted[n - 1] ?? 0,
      avg: Math.round(sum / n),
      p50: this.percentile(sorted, 50),
      p95: this.percentile(sorted, 95),
      p99: this.percentile(sorted, 99),
    };
  }

  private percentile(sorted: number[], p: number): number {
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    const clamped = Math.max(0, index);
    return sorted[clamped] ?? 0;
  }
}
