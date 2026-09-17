package dev.coldstart.ordersapi;

import dev.coldstart.ordersapi.lab.StartupProbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrdersApiApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(OrdersApiApplication.class);
		StartupProbe.fromEnvironment().ifPresent(probe -> probe.watch(application));
		application.run(args);
	}

}
