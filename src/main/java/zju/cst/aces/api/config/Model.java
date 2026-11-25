package zju.cst.aces.api.config;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum Model {
    GPT_3_5_TURBO("gpt-3.5-turbo", new ModelConfig.Builder()
            .withModelName("gpt-3.5-turbo")
            .withUrl("https://api.gptsapi.net/v1/chat/completions")
            .withContextLength(16385)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    GPT_3_5_TURBO_1106("gpt-3.5-turbo-1106", new ModelConfig.Builder()
            .withModelName("gpt-3.5-turbo-1106")
            .withUrl("https://api.gptsapi.net/v1/chat/completions")
            .withContextLength(16385)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    GPT_4O("gpt-4o", new ModelConfig.Builder()
            .withModelName("gpt-4o")
            .withUrl("https://api.gptsapi.net/v1/chat/completions")
            .withContextLength(122880) //120k
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    GPT_4O_MINI("gpt-4o-mini", new ModelConfig.Builder()
            .withModelName("gpt-4o-mini")
            .withUrl("https://api.gptsapi.net/v1/chat/completions")
            .withContextLength(122880) //120k
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    GPT_4O_MINI_0718("gpt-4o-mini-2024-07-18", new ModelConfig.Builder()
            .withModelName("gpt-4o-mini-2024-07-18")
            .withUrl("https://api.gptsapi.net/v1/chat/completions")
            .withContextLength(10922) //120k
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    CODE_LLAMA("code-llama", new ModelConfig.Builder()
            .withModelName("code-llama")
            .withUrl(null)
            .withContextLength(16385)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    // 添加更多模型
    CODE_QWEN("codeqwen:v1.5-chat", new ModelConfig.Builder()
            .withModelName("codeqwen:v1.5-chat")
            .withUrl(null)
            .withContextLength(16385)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    CODESTRAL_2405("codestral-2405", new ModelConfig.Builder()
            .withModelName("codestral-2405")
            .withUrl(null)
            .withContextLength(32000)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    CODESTRAL_2501("codestral-2501", new ModelConfig.Builder()
            .withModelName("codestral-latest")
            .withUrl(null)
            .withContextLength(256000)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    // Codestral 25.08 by version (codestral-latest may change behind the same name)
    CODESTRAL_2508("codestral-2508", new ModelConfig.Builder()
            .withModelName("codestral-2508")
            .withUrl(null)
            .withContextLength(256000)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    // Open-weights models served locally with Ollama (OpenAI-compatible API, -Durl=http://127.0.0.1:<port>/v1/chat/completions,
    // any API key). The context length is the one of the server (OLLAMA_CONTEXT_LENGTH)
    DEVSTRAL_SMALL_2("devstral-small-2", new ModelConfig.Builder()
            .withModelName("devstral-small-2")
            .withUrl(null)
            .withContextLength(32768)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    // Open-weights line-up of the group (FaultLocation), by Ollama tag: 32 GiB (A100), 128 and 64 GiB (B200).
    // Reasoning (Qwen3.8) and seed are set per run with -DextraPayload (e.g. {"think":false,"seed":42})
    QWEN3_8_27B("qwen3.8:27b", new ModelConfig.Builder()
            .withModelName("qwen3.8:27b")
            .withUrl(null)
            .withContextLength(32768)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    DEVSTRAL_2_123B("devstral-2:123b", new ModelConfig.Builder()
            .withModelName("devstral-2:123b")
            .withUrl(null)
            .withContextLength(32768)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    QWEN3_8_FLASH_NEXT("qwen3.8-flash-next:125b-a6b-q4_K_M", new ModelConfig.Builder()
            .withModelName("qwen3.8-flash-next:125b-a6b-q4_K_M")
            .withUrl(null)
            .withContextLength(32768)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    DEVSTRAL_SMALL_2_FP16("devstral-small-2:24b-instruct-2512-fp16", new ModelConfig.Builder()
            .withModelName("devstral-small-2:24b-instruct-2512-fp16")
            .withUrl(null)
            .withContextLength(32768)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    QWEN3_8_27B_BF16("qwen3.8:27b-bf16", new ModelConfig.Builder()
            .withModelName("qwen3.8:27b-bf16")
            .withUrl(null)
            .withContextLength(32768)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .build()),
    GPT_5_4_MINI("gpt-5.4-mini", new ModelConfig.Builder()
            .withModelName("gpt-5.4-mini")
            .withUrl("https://api.openai.com/v1/chat/completions")
            .withContextLength(256000)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .withSupportsRandomSeed(false)
            .withUseMaxCompletionTokens(true)
            .build()),
    DEEPSEEK_V4_FLASH("deepseek-v4-flash", new ModelConfig.Builder()
            .withModelName("deepseek-v4-flash")
            .withUrl("https://api.deepseek.com/v1/chat/completions")
            .withContextLength(256000)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .withDisableThinking(true)
            .build()),
    // The same API model as DEEPSEEK_V4_FLASH, with reasoning (temperature and penalties are ignored in this mode)
    DEEPSEEK_V4_FLASH_THINKING("deepseek-v4-flash-thinking", new ModelConfig.Builder()
            .withModelName("deepseek-v4-flash")
            .withUrl("https://api.deepseek.com/v1/chat/completions")
            .withContextLength(256000)
            .withTemperature(0.5)
            .withFrequencyPenalty(0)
            .withPresencePenalty(0)
            .withEnableThinking(true)
            .build());

    private final String modelName;
    private final ModelConfig defaultConfig;

    Model(String modelName, ModelConfig defaultConfig) {
        this.modelName = modelName;
        this.defaultConfig = defaultConfig;
    }

    public String getModelName() {
        return modelName;
    }

    public ModelConfig getDefaultConfig() {
        return defaultConfig;
    }

    public static Model fromString(String modelName) {
        for (Model model : Model.values()) {
            if (model.getModelName().equalsIgnoreCase(modelName)) {
                return model;
            }
        }
        throw new IllegalArgumentException("No Model with name " + modelName +
                "\nSupport models: " + Arrays.stream(Model.values()).map(Model::getModelName).collect(Collectors.joining(", ")));
    }
}
