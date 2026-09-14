package dev.coldstart.orders.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CgroupMetricsTests {

	private static final String CPU_STAT = """
			usage_usec 8123456
			user_usec 7000000
			system_usec 1123456
			nr_periods 1200
			nr_throttled 300
			throttled_usec 4500000
			""";

	@TempDir
	Path directory;

	@Test
	void parsesCgroupFiles() {
		assertThat(CgroupMetrics.stat(CPU_STAT, "nr_periods")).isEqualTo(1200);
		assertThat(CgroupMetrics.stat(CPU_STAT, "missing")).isNaN();
		assertThat(CgroupMetrics.stat(null, "nr_periods")).isNaN();
		assertThat(CgroupMetrics.cpuLimit("50000 100000\n")).isEqualTo(0.5);
		assertThat(CgroupMetrics.cpuLimit("max 100000")).isNaN();
		assertThat(CgroupMetrics.bytes("2147483648\n")).isEqualTo(2147483648d);
		assertThat(CgroupMetrics.bytes("max\n")).isNaN();
	}

	@Test
	void publishesWhatTheKernelEnforces() throws IOException {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new CgroupMetrics(Files.writeString(directory.resolve("cpu.stat"), CPU_STAT),
				Files.writeString(directory.resolve("cpu.max"), "100000 100000\n"),
				Files.writeString(directory.resolve("memory.current"), "734003200\n"),
				Files.writeString(directory.resolve("memory.max"), "2147483648\n"))
			.bindTo(registry);

		assertThat(registry.get("cgroup.cpu.usage").functionCounter().count()).isCloseTo(8.123456, within(1e-9));
		assertThat(registry.get("cgroup.cpu.periods").functionCounter().count()).isEqualTo(1200);
		assertThat(registry.get("cgroup.cpu.throttled.periods").functionCounter().count()).isEqualTo(300);
		assertThat(registry.get("cgroup.cpu.throttled").functionCounter().count()).isCloseTo(4.5, within(1e-9));
		assertThat(registry.get("cgroup.cpu.limit").gauge().value()).isEqualTo(1.0);
		assertThat(registry.get("cgroup.memory.usage").gauge().value()).isEqualTo(734003200d);
		assertThat(registry.get("cgroup.memory.limit").gauge().value()).isEqualTo(2147483648d);
	}

	@Test
	void publishesNothingWithoutCgroupV2() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Path absent = directory.resolve("absent");

		new CgroupMetrics(absent, absent, absent, absent).bindTo(registry);

		assertThat(registry.getMeters()).isEmpty();
	}

}
