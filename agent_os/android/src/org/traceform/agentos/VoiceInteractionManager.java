package org.traceform.agentos;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import org.json.JSONObject;

public final class VoiceInteractionManager {
    private static final String TAG = "VoiceInteraction";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public interface VoiceCallback {
        void onSpeechDetected(boolean isSpeech, float energyRms);
        void onCommandRecognized(String command, float confidence);
        void onError(String message);
    }

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;

    /**
     * Process synthetic or recorded PCM frames directly through Rust NEON SIMD DSP.
     */
    public static JSONObject processPcmFrame(short[] samples) {
        try {
            String json = NativeAgentCore.analyzeVoicePcm(samples);
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to analyze voice PCM: " + e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * Classifies voice intent using offline phonetic / keyword spotting.
     */
    public static JSONObject classifyIntent(String transcript) {
        try {
            String json = NativeAgentCore.classifyVoiceIntent(transcript);
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to classify voice intent: " + e.getMessage());
            return new JSONObject();
        }
    }

    public synchronized void startListening(VoiceCallback callback) {
        if (isRecording) return;

        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize <= 0) {
                bufferSize = 2048;
            }

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (callback != null) callback.onError("Microphone hardware initialization failed");
                return;
            }

            audioRecord.startRecording();
            isRecording = true;

            final int finalBufSize = bufferSize;
            recordingThread = new Thread(() -> {
                short[] buffer = new short[finalBufSize];
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        try {
                            String resultJson = NativeAgentCore.analyzeVoicePcm(buffer);
                            JSONObject obj = new JSONObject(resultJson);
                            boolean isSpeech = obj.optBoolean("is_speech", false);
                            double energy = obj.optDouble("energy_rms", 0.0);

                            if (callback != null) {
                                callback.onSpeechDetected(isSpeech, (float) energy);
                            }
                        } catch (Exception ignored) {}
                    } else if (read < 0) {
                        Log.e(TAG, "Audio hardware read error: " + read);
                        if (callback != null) {
                            callback.onError("Audio hardware read error: " + read);
                        }
                        break;
                    }
                }
            });
            recordingThread.start();
            Log.i(TAG, "Offline voice interaction engine started listening (16kHz PCM)");
        } catch (SecurityException se) {
            if (callback != null) callback.onError("RECORD_AUDIO permission not granted");
        } catch (Exception e) {
            if (callback != null) callback.onError("Error starting voice recorder: " + e.getMessage());
        }
    }

    public synchronized void stopListening() {
        isRecording = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
        Log.i(TAG, "Voice interaction engine stopped");
    }
}
