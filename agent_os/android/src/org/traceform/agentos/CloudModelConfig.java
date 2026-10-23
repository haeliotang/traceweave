package org.traceform.agentos;

import android.content.Context;
import android.content.SharedPreferences;

final class CloudModelConfig {
    private static final String PREFS_NAME = "agent_os_cloud_model_prefs";
    private static final String KEY_ENABLED = "cloud_model_enabled";
    private static final String KEY_ENDPOINT_URL = "cloud_endpoint_url";
    private static final String KEY_API_KEY = "cloud_api_key";
    private static final String KEY_MODEL_NAME = "cloud_model_name";
    private static final String KEY_TIMEOUT_MILLIS = "cloud_timeout_millis";
    private static final String KEY_TEMPERATURE = "cloud_temperature";

    static final int DEFAULT_TIMEOUT_MILLIS = 4000;
    static final double DEFAULT_TEMPERATURE = 0.1;
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    final boolean enabled;
    final String endpointUrl;
    final String apiKey;
    final String modelName;
    final int timeoutMillis;
    final double temperature;

    CloudModelConfig(boolean enabled, String endpointUrl, String apiKey, String modelName,
                     int timeoutMillis, double temperature) {
        this.enabled = enabled;
        this.endpointUrl = endpointUrl == null ? "" : endpointUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = modelName == null || modelName.trim().isEmpty() ? DEFAULT_MODEL : modelName.trim();
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        this.temperature = temperature >= 0.0 && temperature <= 1.0 ? temperature : DEFAULT_TEMPERATURE;
    }

    boolean isConfigured() {
        return enabled && !endpointUrl.isEmpty();
    }

    static CloudModelConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new CloudModelConfig(
            prefs.getBoolean(KEY_ENABLED, false),
            prefs.getString(KEY_ENDPOINT_URL, ""),
            prefs.getString(KEY_API_KEY, ""),
            prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL),
            prefs.getInt(KEY_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS),
            Double.longBitsToDouble(prefs.getLong(KEY_TEMPERATURE, Double.doubleToLongBits(DEFAULT_TEMPERATURE)))
        );
    }

    void save(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(KEY_ENABLED, enabled);
        editor.putString(KEY_ENDPOINT_URL, endpointUrl);
        editor.putString(KEY_API_KEY, apiKey);
        editor.putString(KEY_MODEL_NAME, modelName);
        editor.putInt(KEY_TIMEOUT_MILLIS, timeoutMillis);
        editor.putLong(KEY_TEMPERATURE, Double.doubleToLongBits(temperature));
        editor.apply();
    }
}
