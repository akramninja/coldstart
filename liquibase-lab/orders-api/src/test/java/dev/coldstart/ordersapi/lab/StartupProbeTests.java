package dev.coldstart.ordersapi.lab;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartupProbeTests {

	@Test
	void killsTheContainerWhenNotReadyInTime() throws InterruptedException {
		CountDownLatch killed = new CountDownLatch(1);
		new StartupProbe(Duration.ofMillis(100), killed::countDown).start();

		assertThat(killed.await(2, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void leavesAReadyApplicationAlone() throws InterruptedException {
		CountDownLatch killed = new CountDownLatch(1);
		StartupProbe probe = new StartupProbe(Duration.ofMillis(300), killed::countDown);
		probe.markReady();
		probe.start();

		assertThat(killed.await(600, TimeUnit.MILLISECONDS)).isFalse();
	}

	@Test
	void isOffUnlessABudgetIsSet() {
		assertThat(StartupProbe.parse(null)).isEmpty();
		assertThat(StartupProbe.parse(" ")).isEmpty();
		assertThat(StartupProbe.parse("0")).isEmpty();
		assertThat(StartupProbe.parse("20")).contains(Duration.ofSeconds(20));
	}

}
