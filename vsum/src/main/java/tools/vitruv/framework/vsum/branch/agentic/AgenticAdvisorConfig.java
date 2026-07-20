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

    /** Backend that speaks Ollama's native {@code /api/chat} protocol (local Ollama). */
    public static final String PROVIDER_OLLAMA = "ollama";
    /** Backend that speaks the OpenAI chat-completions protocol (e.g. self-hosted Open WebUI). */
    public static final String PROVIDER_OPENAI = "openai";

    private static final String DEFAULT_PROVIDER = PROVIDER_OLLAMA;
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";
    private static final String DEFAULT_API_KEY_ENV = "VITRUV_LLM_API_KEY";
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_ITERATIONS = 6;
    private static final int DEFAULT_DEADLINE_SECONDS = 120;
    private static final double DEFAULT_TEMPERATURE = 0.0;

    /** Master switch. When {@code false} the advisor always returns "no opinion". */
    private boolean enabled = true;

    /**
     * Which backend protocol to use: {@link #PROVIDER_OLLAMA} (default, local
     * Ollama) or {@link #PROVIDER_OPENAI} (an OpenAI-compatible server such as a
     * self-hosted Open WebUI). This lets a team point the whole advisor at a
     * shared hosted model without any code change.
     */
    private String provider = DEFAULT_PROVIDER;

    /**
     * Base URL of the backend (no trailing slash). For {@link #PROVIDER_OLLAMA}
     * this is the Ollama server (e.g. {@code http://localhost:11434}); for
     * {@link #PROVIDER_OPENAI} it is the OpenAI-compatible base (e.g.
     * {@code https://open-webui.example.edu/api}).
     */
    private String endpoint = DEFAULT_ENDPOINT;

    /** Model identifier: an Ollama tag, or the model name as exposed by the hosted server. */
    private String model = DEFAULT_MODEL;

    /**
     * Name of the environment variable that holds the API key for
     * {@link #PROVIDER_OPENAI}. The key <em>value</em> is deliberately never
     * stored here, so this config file can be committed and shared: each user
     * exports their own key under this variable. Unused for {@link #PROVIDER_OLLAMA}.
     */
    private String apiKeyEnv = DEFAULT_API_KEY_ENV;

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
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        String normalizedProvider = provider.trim().toLowerCase();
        if (!PROVIDER_OLLAMA.equals(normalizedProvider) && !PROVIDER_OPENAI.equals(normalizedProvider)) {
            throw new IllegalArgumentException(
                    "provider must be '" + PROVIDER_OLLAMA + "' or '" + PROVIDER_OPENAI + "' but was: " + provider);
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (PROVIDER_OPENAI.equals(normalizedProvider) && (apiKeyEnv == null || apiKeyEnv.isBlank())) {
            throw new IllegalArgumentException("apiKeyEnv must not be blank when provider is '" + PROVIDER_OPENAI + "'");
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

    /** Returns the backend provider, normalized to lower case ({@code ollama} / {@code openai}). */
    public String getProvider() { return provider == null ? DEFAULT_PROVIDER : provider.trim().toLowerCase(); }

    /** True when the configured provider is the OpenAI-compatible backend. */
    public boolean isOpenAiProvider() { return PROVIDER_OPENAI.equals(getProvider()); }

    public String getEndpoint() { return endpoint; }
    public String getModel() { return model; }

    /** Name of the environment variable holding the API key (never the key itself). */
    public String getApiKeyEnv() { return apiKeyEnv; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getMaxIterations() { return maxIterations; }
    public int getDeadlineSeconds() { return deadlineSeconds; }
    public double getTemperature() { return temperature; }

    public AgenticAdvisorConfig setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public AgenticAdvisorConfig setProvider(String provider) { this.provider = provider; return this; }
    public AgenticAdvisorConfig setEndpoint(String endpoint) { this.endpoint = endpoint; return this; }
    public AgenticAdvisorConfig setModel(String model) { this.model = model; return this; }
    public AgenticAdvisorConfig setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; return this; }

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
        // Note: no secret is present to leak — only the env-var *name* is stored.
        return "AgenticAdvisorConfig{enabled=" + enabled
                + ", provider='" + provider + '\''
                + ", endpoint='" + endpoint + '\''
                + ", model='" + model + '\''
                + ", apiKeyEnv='" + apiKeyEnv + '\''
                + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                + ", maxIterations=" + maxIterations
                + ", deadlineSeconds=" + deadlineSeconds
                + ", temperature=" + temperature + '}';
    }
}
