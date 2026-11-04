package io.kestra.plugin.datadog;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

@KestraTest
public abstract class AbstractDatadogTest {
    protected static final String API_KEY = "api-key-secret-value";
    protected static final String APP_KEY = "app-key-secret-value";

    @Inject
    protected RunContextFactory runContextFactory;

    protected MockDatadogServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockDatadogServer();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    protected RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
