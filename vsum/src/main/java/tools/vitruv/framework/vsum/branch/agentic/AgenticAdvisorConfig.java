package tools.vitruv.framework.vsum.branch.agentic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the local, tool-using {@link AgenticConflictResolutionAdvisor}.
 *
 * <p>The advisor talks to a locally hosted <a href="https://ollama.com">Ollama</a>
 * server; no code or conflict data ever leaves the machine and no third-party API
 * is contacted. All knobs have safe defaults so the advisor works out of the box
 * against a stock {@code ollama serve} on the default port.
 *
 * <p>Persisted as {@code .vitruvius/config/agentic-advisor.json}, mirroring the
 * load/save pattern of {@link tools.vitruv.framework.vsum.branch.data.SeverityThresholds}.
 * When the file is absent, {@link #defaults()} is returned so behaviour is
 * identical to a fresh checkout.
 */
public class AgenticAdvisorConfig {

    private static final String CONFIG_FILE = "agentic-advisor.json";

    private static final String DEFAULT_ENDPOINT = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_ITERATIONS = 6;
    private static final int DEFAULT_DEADLINE_SECONDS = 120;
    private static final double DEFAULT_TEMPERATURE = 0.0;

    /** Master switch. When {@code false} the advisor always returns "no opinion". */
    private boolean enabled = true;

    /** Base URL of the local Ollama server (no trailing slash). */
    private String endpoint = DEFAULT_ENDPOINT;

    /** Ollama model tag, e.g. {@code qwen2.5-coder:14b} or {@code qwen2.5-coder:7b}. */
    private String model = DEFAULT_MODEL;

    /** Per-request HTTP timeout in seconds. */
    private int requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;

    /** Maximum reasoning/tool-call iterations before the loop gives up. */
    private int maxIterations = DEFAULT_MAX_ITERATIONS;

    /** Overall wall-clock budget for a single {@code propose(...)} call, in seconds. */
    private int deadlineSeconds = DEFAULT_DEADLINE_SECONDS;

    /** Sampling temperature. {@code 0.0} keeps decisions as deterministic as the model allows. */
    private double temperature = DEFAULT_TEMPERATURE;

    /** No-arg constructor for Gson and for callers that then override individual fields. */
    public AgenticAdvisorConfig() {
    }

    /** Returns a configuration with all defaults. */
    public static AgenticAdvisorConfig defaults() {
        return new AgenticAdvisorConfig();
    }

    /**
     * Loads the configuration from the given {@code .vitruvius/config/} directory.
     * Returns {@link #defaults()} when the file does not exist. Any missing field
     * in a partial file keeps its default value.
     *
     * @param configDir the {@code .vitruvius/config/} directory.
     * @return the loaded or default configuration.
     * @throws IOException if the file exists but cannot be read or parsed.
     */
    public static AgenticAdvisorConfig load(Path configDir) throws IOException {
        Path file = configDir.resolve(CONFIG_FILE);
        if (!Files.exists(file)) {
            return defaults();
        }
        String json = Files.readString(file);
        AgenticAdvisorConfig loaded = new Gson().fromJson(json, AgenticAdvisorConfig.class);
        if (loaded == null) {
            return defaults();
        }
        loaded.validate();
        return loaded;
    }

    /**
     * Saves this configuration to the given {@code .vitruvius/config/} directory,
     * creating it if necessary.
     */
    public void save(Path configDir) throws IOException {
        validate();
        Files.createDirectories(configDir);
        Path file = configDir.resolve(CONFIG_FILE);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(file, gson.toJson(this));
    }

    private void validate() {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (requestTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be > 0");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be > 0");
        }
        if (deadlineSeconds <= 0) {
            throw new IllegalArgumentException("deadlineSeconds must be > 0");
        }
        if (temperature < 0.0 || Double.isNaN(temperature)) {
            throw new IllegalArgumentException("temperature must be >= 0");
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getEndpoint() { return endpoint; }
    public String getModel() { return model; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getMaxIterations() { return maxIterations; }
    public int getDeadlineSeconds() { return deadlineSeconds; }
    public double getTemperature() { return temperature; }

    public AgenticAdvisorConfig setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public AgenticAdvisorConfig setEndpoint(String endpoint) { this.endpoint = endpoint; return this; }
    public AgenticAdvisorConfig setModel(String model) { this.model = model; return this; }

    public AgenticAdvisorConfig setRequestTimeoutSeconds(int seconds) {
        this.requestTimeoutSeconds = seconds;
        return this;
    }

    public AgenticAdvisorConfig setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
        return this;
    }

    public AgenticAdvisorConfig setDeadlineSeconds(int deadlineSeconds) {
        this.deadlineSeconds = deadlineSeconds;
        return this;
    }

    public AgenticAdvisorConfig setTemperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    public String toString() {
        return "AgenticAdvisorConfig{enabled=" + enabled
                + ", endpoint='" + endpoint + '\''
                + ", model='" + model + '\''
                + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                + ", maxIterations=" + maxIterations
                + ", deadlineSeconds=" + deadlineSeconds
                + ", temperature=" + temperature + '}';
    }
}
