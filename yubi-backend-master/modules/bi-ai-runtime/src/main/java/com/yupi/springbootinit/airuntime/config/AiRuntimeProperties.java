package com.yupi.springbootinit.airuntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "bi.ai")
public class AiRuntimeProperties {
    private String provider = "zhipu";
    private String defaultModel = "glm-4-flash";
    private List<String> allowedModels = new ArrayList<>(List.of("glm-4-flash"));
    private List<String> fallbackModels = new ArrayList<>();
    private BigDecimal temperature = new BigDecimal("0.2");
    private Integer connectTimeoutMs = 10000;
    private Integer readTimeoutMs = 60000;
    private boolean startupValidation = false;
    private Retry retry = new Retry();
    private Map<String, ModelPricing> pricing = defaultPricing();
    private Zhipu zhipu = new Zhipu();

    private static Map<String, ModelPricing> defaultPricing() {
        Map<String, ModelPricing> map = new LinkedHashMap<>();
        map.put("glm-4-flash", new ModelPricing(new BigDecimal("0.1"), new BigDecimal("0.1")));
        map.put("glm-4-air", new ModelPricing(new BigDecimal("1.0"), new BigDecimal("1.0")));
        map.put("glm-4-plus", new ModelPricing(new BigDecimal("50.0"), new BigDecimal("50.0")));
        return map;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public List<String> getAllowedModels() { return allowedModels; }
    public void setAllowedModels(List<String> allowedModels) { this.allowedModels = allowedModels; }
    public List<String> getFallbackModels() { return fallbackModels; }
    public void setFallbackModels(List<String> fallbackModels) { this.fallbackModels = fallbackModels; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public Integer getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(Integer readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public boolean isStartupValidation() { return startupValidation; }
    public void setStartupValidation(boolean startupValidation) { this.startupValidation = startupValidation; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
    public Map<String, ModelPricing> getPricing() { return pricing; }
    public void setPricing(Map<String, ModelPricing> pricing) { this.pricing = pricing; }
    public Zhipu getZhipu() { return zhipu; }
    public void setZhipu(Zhipu zhipu) { this.zhipu = zhipu; }

    public static class Retry {
        private int maxRetries = 2;
        private long initialBackoffMs = 500L;
        private long maxBackoffMs = 4000L;
        private double jitterRatio = 0.2d;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
        public double getJitterRatio() { return jitterRatio; }
        public void setJitterRatio(double jitterRatio) { this.jitterRatio = jitterRatio; }
    }

    public static class ModelPricing {
        private BigDecimal inputPerMillionCny = BigDecimal.ZERO;
        private BigDecimal outputPerMillionCny = BigDecimal.ZERO;

        public ModelPricing() {}

        public ModelPricing(BigDecimal inputPerMillionCny, BigDecimal outputPerMillionCny) {
            this.inputPerMillionCny = inputPerMillionCny;
            this.outputPerMillionCny = outputPerMillionCny;
        }

        public BigDecimal getInputPerMillionCny() { return inputPerMillionCny; }
        public void setInputPerMillionCny(BigDecimal inputPerMillionCny) { this.inputPerMillionCny = inputPerMillionCny; }
        public BigDecimal getOutputPerMillionCny() { return outputPerMillionCny; }
        public void setOutputPerMillionCny(BigDecimal outputPerMillionCny) { this.outputPerMillionCny = outputPerMillionCny; }
    }

    public static class Zhipu {
        private String apiKey;
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
