package dev.coldstart.orders.lab;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import com.zaxxer.hikari.HikariDataSource;
import dev.coldstart.orders.LabProperties;
import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Logs, once the application is ready, the five defaults as the running JVM and Spring beans
 * actually have them. Configuration files say what someone meant to set; this says what runs.
 * Record it with every result: it proves the scenario ran under the condition it claims.
 * <p>
 * The same values are served live at {@code /actuator/info}, under {@code defaults}.
 */
@Component
public class EffectiveDefaults implements InfoContributor {

	private static final Logger logger = LoggerFactory.getLogger(EffectiveDefaults.class);

	private static final String RULE = "=".repeat(72);

	private final ApplicationContext context;

	EffectiveDefaults(ApplicationContext context) {
		this.context = context;
	}

	@EventListener(ApplicationReadyEvent.class)
	void logAtStartup() {
		// One statement, so the block is never interleaved with other log lines.
		logger.info(capture(context).report());
	}

	@Override
	public void contribute(Info.Builder builder) {
		builder.withDetail("defaults", capture(context));
	}

	/**
	 * @param maxHeapBytes the heap the JVM will grow to
	 * @param maxRamPercentage value and origin, e.g. {@code 25.0 (DEFAULT)}
	 * @param memoryLimitBytes cgroup {@code memory.max}, -1 when unlimited or not in a container
	 * @param cpuLimitCores cgroup {@code cpu.max}, NaN when unlimited or not in a container
	 * @param tomcatMaxThreads -1 when not a Tomcat thread pool
	 * @param taskCorePoolSize -1 when not a {@link ThreadPoolTaskExecutor}, as are max and queue
	 */
	public record Snapshot(long maxHeapBytes, String maxRamPercentage, long memoryLimitBytes, String javaOptsAppend,
			int availableProcessors, double cpuLimitCores, String garbageCollectors, int tomcatMaxThreads,
			boolean openInView, LabProperties.ReadModel orderReadModel, int hikariMaximumPoolSize, String taskExecutor,
			int taskCorePoolSize, int taskMaxPoolSize, int taskQueueCapacity, String cacheManager,
			String caffeineSpec) {

		public double heapShareOfMemoryLimit() {
			return (memoryLimitBytes > 0) ? (double) maxHeapBytes / memoryLimitBytes : Double.NaN;
		}

		public String report() {
			StringBuilder block = new StringBuilder(1024).append('\n').append(RULE).append('\n');
			block.append(" DEFAULTS IN EFFECT (read from the running JVM and Spring beans)\n");
			line(block, "1 maxHeap", mebibytes(maxHeapBytes));
			line(block, "1 MaxRAMPercentage", maxRamPercentage);
			line(block, "1 memoryLimit", mebibytes(memoryLimitBytes));
			line(block, "1 heap / memory limit", Double.isNaN(heapShareOfMemoryLimit()) ? "n/a"
					: String.format(Locale.ROOT, "%.1f%%", heapShareOfMemoryLimit() * 100));
			line(block, "1 JAVA_OPTS_APPEND", orUnset(javaOptsAppend));
			line(block, "2 availableProcessors", availableProcessors);
			line(block, "2 containerCpuLimit", Double.isNaN(cpuLimitCores) ? "unlimited" : cpuLimitCores + " cores");
			line(block, "2 garbageCollectors", garbageCollectors);
			line(block, "2 tomcat.maxThreads", tomcatMaxThreads);
			line(block, "3 openInView", openInView);
			line(block, "3 orderReadModel", orderReadModel);
			line(block, "3 hikari.maximumPoolSize", hikariMaximumPoolSize);
			line(block, "4 taskExecutor", taskExecutor);
			line(block, "4 task core/max/queue", taskCorePoolSize + " / " + taskMaxPoolSize + " / " + taskQueueCapacity);
			line(block, "5 cacheManager", cacheManager);
			line(block, "5 caffeineSpec", orUnset(caffeineSpec));
			return block.append(RULE).toString();
		}

		private static void line(StringBuilder block, String key, Object value) {
			block.append(String.format(Locale.ROOT, " %-26s : %s\n", key, value));
		}

		private static String mebibytes(long bytes) {
			return (bytes < 0) ? "unlimited"
					: String.format(Locale.ROOT, "%d MiB (%d bytes)", bytes / (1024 * 1024), bytes);
		}

		private static String orUnset(String value) {
			return (value != null && !value.isBlank()) ? value : "<unset>";
		}

	}

	public static Snapshot capture(ApplicationContext context) {
		double memoryLimit = CgroupMetrics.bytes(CgroupMetrics.read(CgroupMetrics.MEMORY_MAX));
		AbstractProtocol<?> tomcat = tomcatProtocol(context);
		Object executor = context.containsBean("applicationTaskExecutor") ? context.getBean("applicationTaskExecutor")
				: null;
		ThreadPoolTaskExecutor pool = (executor instanceof ThreadPoolTaskExecutor threadPool) ? threadPool : null;
		return new Snapshot(Runtime.getRuntime().maxMemory(), vmOption("MaxRAMPercentage"),
				Double.isNaN(memoryLimit) ? -1 : (long) memoryLimit, System.getenv("JAVA_OPTS_APPEND"),
				Runtime.getRuntime().availableProcessors(),
				CgroupMetrics.cpuLimit(CgroupMetrics.read(CgroupMetrics.CPU_MAX)),
				ManagementFactory.getGarbageCollectorMXBeans()
					.stream()
					.map(GarbageCollectorMXBean::getName)
					.collect(Collectors.joining(", ")),
				(tomcat != null) ? tomcat.getMaxThreads() : -1,
				context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class).length > 0,
				context.getBean(LabProperties.class).orders().readModel(), hikariMaximumPoolSize(context),
				describe(executor), (pool != null) ? pool.getCorePoolSize() : -1,
				(pool != null) ? pool.getMaxPoolSize() : -1, (pool != null) ? pool.getQueueCapacity() : -1,
				context.getBeanProvider(CacheManager.class)
					.stream()
					.map((manager) -> manager.getClass().getSimpleName())
					.findFirst()
					.orElse("none"),
				context.getEnvironment().getProperty("spring.cache.caffeine.spec"));
	}

	private static AbstractProtocol<?> tomcatProtocol(ApplicationContext context) {
		if (context instanceof WebServerApplicationContext web && web.getWebServer() instanceof TomcatWebServer tomcat
				&& tomcat.getTomcat().getConnector().getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
			return protocol;
		}
		return null;
	}

	private static int hikariMaximumPoolSize(ApplicationContext context) {
		return context.getBeanProvider(DataSource.class)
			.stream()
			.filter(HikariDataSource.class::isInstance)
			.mapToInt((dataSource) -> ((HikariDataSource) dataSource).getMaximumPoolSize())
			.findFirst()
			.orElse(-1);
	}

	private static String describe(Object executor) {
		if (executor instanceof SimpleAsyncTaskExecutor simple) {
			return "SimpleAsyncTaskExecutor (concurrencyLimit " + simple.getConcurrencyLimit() + ")";
		}
		return (executor != null) ? executor.getClass().getSimpleName() : "none";
	}

	/** Value and origin, e.g. {@code 25.0 (DEFAULT)} or {@code 70.0 (VM_CREATION)}. */
	private static String vmOption(String name) {
		try {
			VMOption option = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption(name);
			return Double.parseDouble(option.getValue()) + " (" + option.getOrigin() + ")";
		}
		catch (RuntimeException unavailable) {
			return "n/a";
		}
	}

}
