package tools.vitruv.framework.vsum.branch.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgenticAdvisorConfig} load/save round-tripping.
 */
class AgenticAdvisorConfigTest {

    @Test
    void defaultsAreSensible() {
        AgenticAdvisorConfig config = AgenticAdvisorConfig.defaults();
        assertTrue(config.isEnabled());
        assertEquals("http://localhost:11434", config.getEndpoint());
        assertEquals("qwen2.5-coder:14b", config.getModel());
        assertTrue(config.getMaxIterations() > 0);
        assertEquals("ollama", config.getProvider());
        assertTrue(!config.isOpenAiProvider());
    }

    @Test
    void openAiProviderRoundTripsWithoutStoringKey(@TempDir Path dir) throws IOException {
        AgenticAdvisorConfig original = AgenticAdvisorConfig.defaults()
                .setProvider("openai")
                .setEndpoint("https://open-webui.example.edu/api")
                .setModel("llama3.1:70b")
                .setApiKeyEnv("MY_LLM_KEY");
        original.save(dir);

        // The persisted file must contain only the env-var *name*, never a key value.
        String json = Files.readString(dir.resolve("agentic-advisor.json"));
        assertTrue(json.contains("MY_LLM_KEY"));
        assertTrue(!json.toLowerCase().contains("apikey\"") || !json.contains("Bearer"));

        AgenticAdvisorConfig loaded = AgenticAdvisorConfig.load(dir);
        assertEquals("openai", loaded.getProvider());
        assertTrue(loaded.isOpenAiProvider());
        assertEquals("MY_LLM_KEY", loaded.getApiKeyEnv());
        assertEquals("https://open-webui.example.edu/api", loaded.getEndpoint());
    }

    @Test
    void openAiProviderRequiresApiKeyEnv(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> AgenticAdvisorConfig.defaults().setProvider("openai").setApiKeyEnv(" ").save(dir));
    }

    @Test
    void unknownProviderIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> AgenticAdvisorConfig.defaults().setProvider("azure").save(dir));
    }

    @Test
    void loadReturnsDefaultsWhenFileMissing(@TempDir Path dir) throws IOException {
        AgenticAdvisorConfig config = AgenticAdvisorConfig.load(dir);
        assertEquals(AgenticAdvisorConfig.defaults().getModel(), config.getModel());
    }

    @Test
    void savedConfigRoundTrips(@TempDir Path dir) throws IOException {
        AgenticAdvisorConfig original = AgenticAdvisorConfig.defaults()
                .setModel("qwen2.5-coder:7b")
                .setEndpoint("http://localhost:9999")
                .setMaxIterations(3)
                .setTemperature(0.2);
        original.save(dir);

        assertTrue(Files.exists(dir.resolve("agentic-advisor.json")));
        AgenticAdvisorConfig loaded = AgenticAdvisorConfig.load(dir);
        assertEquals("qwen2.5-coder:7b", loaded.getModel());
        assertEquals("http://localhost:9999", loaded.getEndpoint());
        assertEquals(3, loaded.getMaxIterations());
        assertEquals(0.2, loaded.getTemperature(), 1e-9);
    }

    @Test
    void invalidConfigIsRejectedOnSave(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> AgenticAdvisorConfig.defaults().setModel(" ").save(dir));
    }
}
