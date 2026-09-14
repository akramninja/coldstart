package dev.coldstart.orders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP against the running server, so Tomcat, the open-in-view interceptor and the async
 * executor behave as they do under load.
 */
final class TestHttp {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient client = HttpClient.newHttpClient();

	private final int port;

	TestHttp(int port) {
		this.port = port;
	}

	HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
	}

	CompletableFuture<HttpResponse<String>> getAsync(String path) {
		return client.sendAsync(request(path).build(), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
		HttpRequest request = request(path).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
	}

}
