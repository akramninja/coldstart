package dev.coldstart.ordersapi.lab;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * Stands in for a Kubernetes startup probe, which Docker Compose doesn't have.
 *
 * <p>The kubelet restarts a container that isn't ready after {@code periodSeconds × failureThreshold}.
 * Here the JVM does it to itself: if the application isn't ready after {@code LAB_STARTUP_PROBE_SECONDS},
 * it halts with exit code 137, the code of a SIGKILL. No shutdown hook runs and nothing is cleaned up,
 * like a container killed at the end of its grace period. Compose's restart policy starts it again.
 *
 * <p>Unset or 0: no probe, the application takes as long as it needs.
 */
public final class StartupProbe {

	static final String BUDGET_VARIABLE = "LAB_STARTUP_PROBE_SECONDS";

	static final int KILLED_EXIT_CODE = 137;

	private static final Logger log = LoggerFactory.getLogger(StartupProbe.class);

	private final Duration budget;

	private final Runnable kill;

	private final CountDownLatch ready = new CountDownLatch(1);

	StartupProbe(Duration budget, Runnable kill) {
		this.budget = budget;
		this.kill = kill;
	}

	public static Optional<StartupProbe> fromEnvironment() {
		return parse(System.getenv(BUDGET_VARIABLE))
			.map(budget -> new StartupProbe(budget, () -> Runtime.getRuntime().halt(KILLED_EXIT_CODE)));
	}

	static Optional<Duration> parse(String seconds) {
		if (seconds == null || seconds.isBlank()) {
			return Optional.empty();
		}
		long value = Long.parseLong(seconds.trim());
		return value > 0 ? Optional.of(Duration.ofSeconds(value)) : Optional.empty();
	}

	public void watch(SpringApplication application) {
		application.addListeners(event -> {
			if (event instanceof ApplicationReadyEvent) {
				markReady();
			}
		});
		start();
	}

	void start() {
		Thread.ofPlatform().daemon().name("startup-probe").start(this::await);
	}

	void markReady() {
		this.ready.countDown();
	}

	private void await() {
		try {
			if (!this.ready.await(this.budget.toMillis(), TimeUnit.MILLISECONDS)) {
				log.error("Startup probe failed: not ready after {} s. Killing the container (exit {}), as the kubelet would.",
						this.budget.toSeconds(), KILLED_EXIT_CODE);
				this.kill.run();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
