package com.bealetech.metrics.reporting;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;
import com.yammer.metrics.reporting.AbstractPollingReporter;
import com.yammer.metrics.stats.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

public class StatsdReporter extends AbstractPollingReporter implements MetricProcessor<Long> {

    public static enum StatType { COUNTER, TIMER, GAUGE }

    private static final Logger LOG = LoggerFactory.getLogger(StatsdReporter.class);

    protected final String prefix;
    protected final MetricPredicate predicate;
    protected final Locale locale = Locale.US;
    protected final Clock clock;
    protected final UDPSocketProvider socketProvider;
    protected final VirtualMachineMetrics vm;
    protected Writer writer;
    protected ByteArrayOutputStream outputData;

    private boolean printVMMetrics = true;

    public StatsdReporter(String host, int port) throws IOException {
        this(Metrics.defaultRegistry(), host, port, null);
    }

    public StatsdReporter(String host, int port, String prefix) throws IOException {
        this(Metrics.defaultRegistry(), host, port, prefix);
    }

    public StatsdReporter(MetricsRegistry metricsRegistry, String host, int port) throws IOException {
        this(metricsRegistry, host, port, null);
    }

    public StatsdReporter(MetricsRegistry metricsRegistry, String host, int port, String prefix) throws IOException {
        this(metricsRegistry,
             prefix,
             MetricPredicate.ALL,
             new DefaultSocketProvider(host, port),
             Clock.defaultClock());
    }

    public StatsdReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, UDPSocketProvider socketProvider, Clock clock) throws IOException {
        this(metricsRegistry, prefix, predicate, socketProvider, clock, VirtualMachineMetrics.getInstance());
    }

    public StatsdReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, UDPSocketProvider socketProvider, Clock clock, VirtualMachineMetrics vm) throws IOException {
        this(metricsRegistry, prefix, predicate, socketProvider, clock, vm, "statsd-reporter");
    }

    public StatsdReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, UDPSocketProvider socketProvider, Clock clock, VirtualMachineMetrics vm, String name) throws IOException {
        super(metricsRegistry, name);

        this.socketProvider = socketProvider;
        this.vm = vm;

        this.clock = clock;

        if (prefix != null) {
            // Pre-append the "." so that we don't need to make anything conditional later.
            this.prefix = prefix + ".";
        } else {
            this.prefix = "";
        }
        this.predicate = predicate;
        this.outputData = new ByteArrayOutputStream();
    }

    public boolean isPrintVMMetrics() {
        return printVMMetrics;
    }

    public void setPrintVMMetrics(boolean printVMMetrics) {
        this.printVMMetrics = printVMMetrics;
    }

    @Override
    public void run() {
        DatagramSocket socket = null;
        try {
            socket = this.socketProvider.get();
            outputData.reset();
            writer = new BufferedWriter(new OutputStreamWriter(this.outputData));

            final long epoch = clock.time() / 1000;
            if (this.printVMMetrics) {
                printVmMetrics(epoch);
            }
            printRegularMetrics(epoch);

            // Send UDP data
            writer.flush();
            DatagramPacket packet = this.socketProvider.newPacket(outputData);
            packet.setData(outputData.toByteArray());
            socket.send(packet);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error writing to StatsD", e);
            } else {
                LOG.warn("Error writing to StatsD: {}", e.getMessage());
            }
            if (writer != null) {
                try {
                    writer.flush();
                } catch (IOException e1) {
                    LOG.error("Error while flushing writer:", e1);
                }
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
            writer = null;
        }
    }

    protected void printVmMetrics(long epoch) {
        // Memory
        sendFloat("jvm.memory.totalInit", StatType.GAUGE, vm.totalInit());
        sendFloat("jvm.memory.totalUsed", StatType.GAUGE, vm.totalUsed());
        sendFloat("jvm.memory.totalMax", StatType.GAUGE, vm.totalMax());
        sendFloat("jvm.memory.totalCommitted", StatType.GAUGE, vm.totalCommitted());

        sendFloat("jvm.memory.heapInit", StatType.GAUGE, vm.heapInit());
        sendFloat("jvm.memory.heapUsed", StatType.GAUGE, vm.heapUsed());
        sendFloat("jvm.memory.heapMax", StatType.GAUGE, vm.heapMax());
        sendFloat("jvm.memory.heapCommitted", StatType.GAUGE, vm.heapCommitted());

        sendFloat("jvm.memory.heapUsage", StatType.GAUGE, vm.heapUsage());
        sendFloat("jvm.memory.nonHeapUsage", StatType.GAUGE, vm.nonHeapUsage());

        for (Map.Entry<String, Double> pool : vm.memoryPoolUsage().entrySet()) {
            sendFloat("jvm.memory.memory_pool_usages." + sanitizeString(pool.getKey()), StatType.GAUGE, pool.getValue());
        }

        // Buffer Pool
        final Map<String, VirtualMachineMetrics.BufferPoolStats> bufferPoolStats = vm.getBufferPoolStats();
        if (!bufferPoolStats.isEmpty()) {
            sendFloat("jvm.buffers.direct.count", StatType.GAUGE, bufferPoolStats.get("direct").getCount());
            sendFloat("jvm.buffers.direct.memoryUsed", StatType.GAUGE, bufferPoolStats.get("direct").getMemoryUsed());
            sendFloat("jvm.buffers.direct.totalCapacity", StatType.GAUGE, bufferPoolStats.get("direct").getTotalCapacity());

            sendFloat("jvm.buffers.mapped.count", StatType.GAUGE, bufferPoolStats.get("mapped").getCount());
            sendFloat("jvm.buffers.mapped.memoryUsed", StatType.GAUGE, bufferPoolStats.get("mapped").getMemoryUsed());
            sendFloat("jvm.buffers.mapped.totalCapacity", StatType.GAUGE, bufferPoolStats.get("mapped").getTotalCapacity());
        }

        sendInt("jvm.daemon_thread_count", StatType.GAUGE, vm.daemonThreadCount());
        sendInt("jvm.thread_count", StatType.GAUGE, vm.threadCount());
        sendInt("jvm.uptime", StatType.GAUGE, vm.uptime());
        sendFloat("jvm.fd_usage", StatType.GAUGE, vm.fileDescriptorUsage());

        for (Map.Entry<Thread.State, Double> entry : vm.threadStatePercentages().entrySet()) {
            sendFloat("jvm.thread-states." + entry.getKey().toString().toLowerCase(), StatType.GAUGE, entry.getValue());
        }

        for (Map.Entry<String, VirtualMachineMetrics.GarbageCollectorStats> entry : vm.garbageCollectors().entrySet()) {
            final String name = "jvm.gc." + sanitizeString(entry.getKey());
            sendInt(name + ".time", StatType.GAUGE, entry.getValue().getTime(TimeUnit.MILLISECONDS));
            sendInt(name + ".runs", StatType.GAUGE, entry.getValue().getRuns());
        }
    }

    protected void printRegularMetrics(long epoch) {
        for (Map.Entry<String,SortedMap<MetricName,Metric>> entry : getMetricsRegistry().groupedMetrics(predicate).entrySet()) {
            for (Map.Entry<MetricName, Metric> subEntry : entry.getValue().entrySet()) {
                final Metric metric = subEntry.getValue();
                if (metric != null) {
                    try {
                        metric.processWith(this, subEntry.getKey(), epoch);
                    } catch (Exception ignored) {
                        LOG.error("Error printing regular metrics:", ignored);
                    }
                }
            }
        }
    }

    @Override
    public void processMeter(MetricName name, Metered meter, Long epoch) throws Exception {
        final String sanitizedName = sanitizeName(name);
        sendInt(sanitizedName + ".count", StatType.GAUGE, meter.count());
        sendFloat(sanitizedName + ".meanRate", StatType.TIMER, meter.meanRate());
        sendFloat(sanitizedName + ".1MinuteRate", StatType.TIMER, meter.oneMinuteRate());
        sendFloat(sanitizedName + ".5MinuteRate", StatType.TIMER, meter.fiveMinuteRate());
        sendFloat(sanitizedName + ".15MinuteRate", StatType.TIMER, meter.fifteenMinuteRate());
    }

    @Override
    public void processCounter(MetricName name, Counter counter, Long epoch) throws Exception {
        sendInt(sanitizeName(name) + ".count", StatType.GAUGE, counter.count());
    }

    @Override
    public void processHistogram(MetricName name, Histogram histogram, Long epoch) throws Exception {
        final String sanitizedName = sanitizeName(name);
        sendSummarizable(sanitizedName, histogram);
        sendSampling(sanitizedName, histogram);
    }

    @Override
    public void processTimer(MetricName name, Timer timer, Long epoch) throws Exception {
        processMeter(name, timer, epoch);
        final String sanitizedName = sanitizeName(name);
        sendSummarizable(sanitizedName, timer);
        sendSampling(sanitizedName, timer);
    }

    @Override
    public void processGauge(MetricName name, Gauge<?> gauge, Long epoch) throws Exception {
        sendObj(sanitizeName(name) + ".count", StatType.GAUGE, gauge.value());
    }

    protected void sendSummarizable(String sanitizedName, Summarizable metric) throws IOException {
        sendFloat(sanitizedName + ".min", StatType.TIMER, metric.min());
        sendFloat(sanitizedName + ".max", StatType.TIMER, metric.max());
        sendFloat(sanitizedName + ".mean", StatType.TIMER, metric.mean());
        sendFloat(sanitizedName + ".stddev", StatType.TIMER, metric.stdDev());
    }

    protected void sendSampling(String sanitizedName, Sampling metric) throws IOException {
        final Snapshot snapshot = metric.getSnapshot();
        sendFloat(sanitizedName + ".median", StatType.TIMER, snapshot.getMedian());
        sendFloat(sanitizedName + ".75percentile", StatType.TIMER, snapshot.get75thPercentile());
        sendFloat(sanitizedName + ".95percentile", StatType.TIMER, snapshot.get95thPercentile());
        sendFloat(sanitizedName + ".98percentile", StatType.TIMER, snapshot.get98thPercentile());
        sendFloat(sanitizedName + ".99percentile", StatType.TIMER, snapshot.get99thPercentile());
        sendFloat(sanitizedName + ".999percentile", StatType.TIMER, snapshot.get999thPercentile());
    }


    protected void sendInt(String name, StatType statType, long value) {
        sendData(name, String.format(locale, "%d", value), statType);
    }

    protected void sendFloat(String name, StatType statType, double value) {
        sendData(name, String.format(locale, "%2.2f", value), statType);
    }

    protected void sendObj(String name, StatType statType, Object value) {
        sendData(name, String.format(locale, "%s", value), statType);
    }

    protected String sanitizeName(MetricName name) {
        final StringBuilder sb = new StringBuilder()
                .append(name.getGroup())
                .append('.')
                .append(name.getType())
                .append('.');
        if (name.hasScope()) {
            sb.append(name.getScope())
                    .append('.');
        }
        return sb.append(name.getName()).toString();
    }

    protected String sanitizeString(String s) {
        return s.replace(' ', '-');
    }

    protected void sendData(String name, String value, StatType statType) {
        String statTypeStr = "";
        switch (statType) {
            case COUNTER:
                statTypeStr = "c";
                break;
            case GAUGE:
                statTypeStr = "g";
                break;
            case TIMER:
                statTypeStr = "ms";
                break;
        }
        
        try {
            if (!prefix.isEmpty()) {
                writer.write(prefix);
            }
            writer.write(sanitizeString(name));
            writer.write(":");
            writer.write(value);
            writer.write("|");
            writer.write(statTypeStr);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            LOG.error("Error sending to StatsD:", e);
        }
    }

}
