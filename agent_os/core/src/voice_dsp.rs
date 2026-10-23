use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceAnalysisResult {
    pub is_speech: bool,
    pub energy_rms: f32,
    pub zero_crossing_rate: f32,
    pub detected_command: Option<String>,
    pub confidence: f32,
}

pub struct VoiceDspEngine {
    pub sample_rate: u32,
    pub energy_threshold: f32,
    pub zcr_threshold: f32,
}

impl Default for VoiceDspEngine {
    fn default() -> Self {
        Self {
            sample_rate: 16000,
            energy_threshold: 0.015,
            zcr_threshold: 0.01,
        }
    }
}

impl VoiceDspEngine {
    pub fn new(sample_rate: u32, energy_threshold: f32) -> Self {
        Self {
            sample_rate,
            energy_threshold,
            zcr_threshold: 0.01,
        }
    }

    /// Process raw 16-bit PCM audio samples (16kHz mono)
    pub fn analyze_frame(&self, samples: &[i16]) -> VoiceAnalysisResult {
        if samples.is_empty() {
            return VoiceAnalysisResult {
                is_speech: false,
                energy_rms: 0.0,
                zero_crossing_rate: 0.0,
                detected_command: None,
                confidence: 0.0,
            };
        }

        let n = samples.len() as f32;
        let mut sum_squares = 0.0f32;
        let mut zero_crossings = 0usize;

        // Compute RMS energy and Zero-Crossing Rate
        #[cfg(target_arch = "aarch64")]
        unsafe {
            use std::arch::aarch64::*;
            let mut i = 0;
            let mut acc_v = vdupq_n_f32(0.0);

            while i + 4 <= samples.len() {
                let s0 = samples[i] as f32 / 32768.0;
                let s1 = samples[i + 1] as f32 / 32768.0;
                let s2 = samples[i + 2] as f32 / 32768.0;
                let s3 = samples[i + 3] as f32 / 32768.0;

                let vals = [s0, s1, s2, s3];
                let v = vld1q_f32(vals.as_ptr());
                acc_v = vfmaq_f32(acc_v, v, v);

                i += 4;
            }

            let mut arr = [0.0f32; 4];
            vst1q_f32(arr.as_mut_ptr(), acc_v);
            sum_squares += arr[0] + arr[1] + arr[2] + arr[3];

            // Tail
            while i < samples.len() {
                let s = samples[i] as f32 / 32768.0;
                sum_squares += s * s;
                i += 1;
            }
        }

        #[cfg(not(target_arch = "aarch64"))]
        {
            for &s in samples {
                let norm = s as f32 / 32768.0;
                sum_squares += norm * norm;
            }
        }

        for i in 1..samples.len() {
            if (samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0) {
                zero_crossings += 1;
            }
        }

        let energy_rms = (sum_squares / n).sqrt();
        let zcr = zero_crossings as f32 / n;

        let is_speech = energy_rms > self.energy_threshold && zcr > self.zcr_threshold;

        VoiceAnalysisResult {
            is_speech,
            energy_rms,
            zero_crossing_rate: zcr,
            detected_command: None,
            confidence: if is_speech { 0.95 } else { 0.1 },
        }
    }

    /// Match recognized text or phoneme tokens to structured Agent OS commands
    pub fn classify_speech_intent(transcript: &str) -> Option<(String, f32)> {
        let text = transcript.trim().to_lowercase();
        if text.contains("天气") || text.contains("weather") {
            Some(("query_weather".to_string(), 0.98))
        } else if text.contains("任务") || text.contains("task") || text.contains("创建") {
            Some(("create_task".to_string(), 0.96))
        } else if text.contains("屏幕") || text.contains("screen") || text.contains("总结") {
            Some(("summarize_screen".to_string(), 0.97))
        } else if text.contains("确认") || text.contains("confirm") || text.contains("同意") {
            Some(("confirm_action".to_string(), 0.99))
        } else if text.contains("取消") || text.contains("cancel") || text.contains("拒绝") {
            Some(("cancel_action".to_string(), 0.99))
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_silence_frame() {
        let engine = VoiceDspEngine::default();
        let silence = vec![0i16; 320]; // 20ms of silence at 16kHz
        let result = engine.analyze_frame(&silence);
        assert!(!result.is_speech);
        assert_eq!(result.energy_rms, 0.0);
    }

    #[test]
    fn test_synthetic_speech_signal() {
        let engine = VoiceDspEngine::default();
        let mut speech = Vec::new();
        for i in 0..640 {
            let val = ((i as f32 * 0.1).sin() * 8000.0) as i16;
            speech.push(val);
        }
        let result = engine.analyze_frame(&speech);
        assert!(result.is_speech);
        assert!(result.energy_rms > 0.05);
    }

    #[test]
    fn test_intent_classification() {
        assert_eq!(
            VoiceDspEngine::classify_speech_intent("帮我查一下北京天气"),
            Some(("query_weather".to_string(), 0.98))
        );
        assert_eq!(
            VoiceDspEngine::classify_speech_intent("总结一下当前屏幕"),
            Some(("summarize_screen".to_string(), 0.97))
        );
        assert_eq!(
            VoiceDspEngine::classify_speech_intent("确认执行这个步骤"),
            Some(("confirm_action".to_string(), 0.99))
        );
    }
}
