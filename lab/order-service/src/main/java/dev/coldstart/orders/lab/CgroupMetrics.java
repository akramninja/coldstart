package dev.coldstart.orders.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.stereotype.Component;

/**
 * The container's CPU and memory as the kernel enforces them, read from cgroup v2 files.
 * <p>
 * The JVM's own metrics can't show either limit. CPU usage averaged over a minute sits well below
 * the limit while the kernel pauses the container at the end of every 100 ms period. The JVM
 * reports its heap, but the OOM killer looks at the container's total. On Kubernetes, cAdvisor
 * publishes the same values as {@code container_cpu_cfs_*} and {@code container_memory_*}.
 * <p>
 * Files are read at scrape time only. Without cgroup v2, no meter is registered rather than
 * publishing misleading zeros.
 */
@Component
class CgroupMetrics implements MeterBinder {

	static final Path CPU_STAT = Path.of("/sys/fs/cgroup/cpu.stat");

	static final Path CPU_MAX = Path.of("/sys/fs/cgroup/cpu.max");

	static final Path MEMORY_CURRENT = Path.of("/sys/fs/cgroup/memory.current");

	static final Path MEMORY_MAX = Path.of("/sys/fs/cgroup/memory.max");

	private final Path cpuStat;

	private final Path cpuMax;

	private final Path memoryCurrent;

	private final Path memoryMax;

	CgroupMetrics() {
		this(CPU_STAT, CPU_MAX, MEMORY_CURRENT, MEMORY_MAX);
	}

	CgroupMetrics(Path cpuStat, Path cpuMax, Path memoryCurrent, Path memoryMax) {
		this.cpuStat = cpuStat;
		this.cpuMax = cpuMax;
		this.memoryCurrent = memoryCurrent;
		this.memoryMax = memoryMax;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		if (Files.isReadable(cpuStat)) {
			FunctionCounter.builder("cgroup.cpu.usage", cpuStat, (path) -> stat(read(path), "usage_usec") / 1e6)
				.baseUnit("seconds")
				.description("CPU time used by the container; its rate is the number of cores in use")
				.register(registry);
			FunctionCounter.builder("cgroup.cpu.periods", cpuStat, (path) -> stat(read(path), "nr_periods"))
				.description("CFS enforcement periods elapsed")
				.register(registry);
			FunctionCounter.builder("cgroup.cpu.throttled.periods", cpuStat, (path) -> stat(read(path), "nr_throttled"))
				.description("CFS periods in which the container used up its quota and was paused")
				.register(registry);
			FunctionCounter.builder("cgroup.cpu.throttled", cpuStat, (path) -> stat(read(path), "throttled_usec") / 1e6)
				.baseUnit("seconds")
				.description("Total time the container was paused by the CFS quota")
				.register(registry);
		}
		if (Files.isReadable(cpuMax)) {
			Gauge.builder("cgroup.cpu.limit", cpuMax, (path) -> cpuLimit(read(path)))
				.baseUnit("cores")
				.description("CPU quota divided by period; NaN when unlimited")
				.register(registry);
		}
		if (Files.isReadable(memoryCurrent)) {
			Gauge.builder("cgroup.memory.usage", memoryCurrent, (path) -> bytes(read(path)))
				.baseUnit("bytes")
				.description("Memory charged to the container, heap and everything else")
				.register(registry);
		}
		if (Files.isReadable(memoryMax)) {
			Gauge.builder("cgroup.memory.limit", memoryMax, (path) -> bytes(read(path)))
				.baseUnit("bytes")
				.description("Container memory limit; NaN when unlimited")
				.register(registry);
		}
	}

	/** {@code null} when unreadable, so a meter reports NaN instead of failing the scrape. */
	static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException unreadable) {
			return null;
		}
	}

	/** One key of a flat-keyed file such as {@code cpu.stat}. */
	static double stat(String content, String key) {
		if (content != null) {
			for (String line : content.split("\n")) {
				String[] parts = line.trim().split("\\s+");
				if (parts.length == 2 && parts[0].equals(key)) {
					return Double.parseDouble(parts[1]);
				}
			}
		}
		return Double.NaN;
	}

	/** {@code cpu.max}: {@code "100000 100000"} is one core, {@code "max 100000"} is no limit. */
	static double cpuLimit(String content) {
		String[] parts = (content != null) ? content.trim().split("\\s+") : new String[0];
		if (parts.length != 2 || parts[0].equals("max")) {
			return Double.NaN;
		}
		return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
	}

	/** {@code memory.max} or {@code memory.current}: bytes, or {@code "max"} for no limit. */
	static double bytes(String content) {
		String value = (content != null) ? content.trim() : "";
		return (value.isEmpty() || value.equals("max")) ? Double.NaN : Double.parseDouble(value);
	}

}
