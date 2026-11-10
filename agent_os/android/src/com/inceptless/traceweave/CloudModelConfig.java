// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class CloudModelConfig {
    private static final String PREFS_NAME = "agent_os_cloud_model_prefs";
    private static final String KEY_ENABLED = "cloud_model_enabled";
    private static final String KEY_ENDPOINT_URL = "cloud_endpoint_url";
    private static final String KEY_API_KEY = "cloud_api_key";
    private static final String KEY_MODEL_NAME = "cloud_model_name";
    private static final String KEY_TIMEOUT_MILLIS = "cloud_timeout_millis";
    private static final String KEY_TEMPERATURE = "cloud_temperature";
    private static final String KEY_ALIAS = "traceweave.agentos.cloud-api-key.v1";
    private static final String API_KEY_PREFIX = "android-keystore:v1:";

    static final int DEFAULT_TIMEOUT_MILLIS = 15000;
    static final double DEFAULT_TEMPERATURE = 0.1;
    static final String DEFAULT_MODEL = "deepseek-flash";

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
        this.timeoutMillis = timeoutMillis >= 10000 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        this.temperature = temperature >= 0.0 && temperature <= 1.0 ? temperature : DEFAULT_TEMPERATURE;
    }

    boolean isConfigured() {
        return enabled && !endpointUrl.isEmpty();
    }

    static CloudModelConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(KEY_API_KEY, "");
        if (!apiKey.isEmpty()) {
            try {
                if (apiKey.startsWith(API_KEY_PREFIX)) {
                    apiKey = decryptApiKey(apiKey.substring(API_KEY_PREFIX.length()));
                } else {
                    String encrypted = API_KEY_PREFIX + encryptApiKey(apiKey);
                    if (prefs.edit().putString(KEY_API_KEY, encrypted).commit()) {
                        // Migrate the legacy plaintext value only after its encrypted replacement is durable.
                    } else {
                        prefs.edit().remove(KEY_API_KEY).commit();
                        apiKey = "";
                    }
                }
            } catch (Exception error) {
                if (!apiKey.startsWith(API_KEY_PREFIX)) prefs.edit().remove(KEY_API_KEY).commit();
                apiKey = "";
            }
        }
        return new CloudModelConfig(
            prefs.getBoolean(KEY_ENABLED, false),
            prefs.getString(KEY_ENDPOINT_URL, ""),
            apiKey,
            prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL),
            prefs.getInt(KEY_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS),
            Double.longBitsToDouble(prefs.getLong(KEY_TEMPERATURE, Double.doubleToLongBits(DEFAULT_TEMPERATURE)))
        );
    }

    boolean save(Context context) {
        String encryptedApiKey;
        try {
            encryptedApiKey = apiKey.isEmpty() ? "" : API_KEY_PREFIX + encryptApiKey(apiKey);
        } catch (Exception error) {
            return false;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(KEY_ENABLED, enabled);
        editor.putString(KEY_ENDPOINT_URL, endpointUrl);
        editor.putString(KEY_API_KEY, encryptedApiKey);
        editor.putString(KEY_MODEL_NAME, modelName);
        editor.putInt(KEY_TIMEOUT_MILLIS, timeoutMillis);
        editor.putLong(KEY_TEMPERATURE, Double.doubleToLongBits(temperature));
        editor.apply();
        return true;
    }

    private static String encryptApiKey(String apiKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(cipher.getIV().length + encrypted.length);
        payload.put(cipher.getIV());
        payload.put(encrypted);
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    }

    private static String decryptApiKey(String encodedPayload) throws Exception {
        byte[] payload = Base64.decode(encodedPayload, Base64.NO_WRAP);
        if (payload.length <= 12) throw new IllegalArgumentException("Invalid encrypted API key");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte[] iv = new byte[12];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}
