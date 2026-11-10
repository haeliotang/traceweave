// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VisualElement {
    pub id: String,
    pub text: String,
    pub bounds: [f32; 4], // [x, y, width, height]
    #[serde(default = "default_confidence")]
    pub confidence: f32,
    #[serde(default = "default_element_type")]
    pub element_type: String, // "button", "text", "input", "icon", "card"
}

fn default_confidence() -> f32 { 0.95 }
fn default_element_type() -> String { "text".to_string() }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VisualScene {
    #[serde(default = "default_width")]
    pub screen_width: u32,
    #[serde(default = "default_height")]
    pub screen_height: u32,
    pub elements: Vec<VisualElement>,
}

fn default_width() -> u32 { 1080 }
fn default_height() -> u32 { 2400 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VisualAnalysisResult {
    pub matched: bool,
    pub target_id: String,
    pub target_text: String,
    pub tap_x: f32,
    pub tap_y: f32,
    pub element_bounds: [f32; 4],
    pub action_type: String,
    pub currency_values: Vec<String>,
    pub confidence: f32,
    pub reasoning: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentEntity {
    pub doc_type: String,
    pub merchant_or_title: String,
    pub total_amount: Option<f64>,
    pub detected_date: Option<String>,
    pub line_items: Vec<String>,
    pub suggested_task: String,
    pub memory_representation: String,
}

/// Analyzes visual layout and finds best interactive target matching user query/intent.
pub fn analyze_visual_scene(scene_json: &str, query: &str) -> Result<String, String> {
    let scene: VisualScene = serde_json::from_str(scene_json)
        .map_err(|e| format!("Visual scene parse error: {}", e))?;

    let q = query.trim().to_lowercase();
    let currency_regex = Regex::new(r"[\$¥€]\s*([0-9]+(?:\.[0-9]{2})?)").map_err(|e| e.to_string())?;

    let mut found_currency = Vec::new();
    let mut best_element: Option<&VisualElement> = None;
    let mut best_score: f32 = -1.0;
    let mut best_action = "click".to_string();

    // Check all elements for text/query affinity
    for elem in &scene.elements {
        let txt_lower = elem.text.to_lowercase();

        // Extract currency values
        for cap in currency_regex.captures_iter(&elem.text) {
            if let Some(m) = cap.get(0) {
                found_currency.push(m.as_str().to_string());
            }
        }

        let mut score = 0.0f32;
        if txt_lower == q {
            score = 1.0;
        } else if txt_lower.contains(&q) {
            score = 0.85;
        } else if q.contains(&txt_lower) && !txt_lower.is_empty() {
            score = 0.70;
        } else {
            // Keyword affinity heuristics
            let keywords = ["pay", "confirm", "submit", "next", "continue", "checkout", "send", "save"];
            for kw in keywords {
                if (q.contains(kw) && txt_lower.contains(kw)) ||
                   (q.contains("buy") && (txt_lower.contains("pay") || txt_lower.contains("order"))) {
                    score = 0.75;
                    break;
                }
            }
        }

        // Bias towards buttons and inputs
        if elem.element_type == "button" {
            score += 0.15;
        } else if elem.element_type == "input" {
            score += 0.10;
        }

        if score > best_score {
            best_score = score;
            best_element = Some(elem);
            best_action = if elem.element_type == "input" { "input".to_string() } else { "click".to_string() };
        }
    }

    if let Some(target) = best_element {
        let tap_x = target.bounds[0] + target.bounds[2] / 2.0;
        let tap_y = target.bounds[1] + target.bounds[3] / 2.0;

        let result = VisualAnalysisResult {
            matched: best_score >= 0.5,
            target_id: target.id.clone(),
            target_text: target.text.clone(),
            tap_x,
            tap_y,
            element_bounds: target.bounds,
            action_type: best_action,
            currency_values: found_currency,
            confidence: (target.confidence * (best_score.min(1.0))).min(1.0),
            reasoning: format!("Found visual target '{}' with match score {:.2}", target.text, best_score),
        };
        serde_json::to_string(&result).map_err(|e| e.to_string())
    } else {
        let result = VisualAnalysisResult {
            matched: false,
            target_id: String::new(),
            target_text: String::new(),
            tap_x: 0.0,
            tap_y: 0.0,
            element_bounds: [0.0; 4],
            action_type: "none".to_string(),
            currency_values: found_currency,
            confidence: 0.0,
            reasoning: format!("No visual element matched query: {}", query),
        };
        serde_json::to_string(&result).map_err(|e| e.to_string())
    }
}

/// Parses physical world document (receipt, invoice, or handwritten note) from OCR tokens.
pub fn parse_visual_document(ocr_json: &str) -> Result<String, String> {
    let elements: Vec<VisualElement> = serde_json::from_str(ocr_json)
        .map_err(|e| format!("OCR tokens parse error: {}", e))?;

    let amount_regex = Regex::new(r"(?:total|due|amount|balance)?[^\d]*([\$¥€]?\s*\d+(?:\.\d{2})?)")
        .map_err(|e| e.to_string())?;
    let date_regex = Regex::new(r"(\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})")
        .map_err(|e| e.to_string())?;

    let mut merchant = String::new();
    let mut total_amount: Option<f64> = None;
    let mut detected_date: Option<String> = None;
    let mut line_items = Vec::new();

    for (idx, elem) in elements.iter().enumerate() {
        let trimmed = elem.text.trim();
        if trimmed.is_empty() {
            continue;
        }

        // Top element is typically the merchant name or invoice header
        if idx == 0 && merchant.is_empty() {
            merchant = trimmed.to_string();
        }

        // Check for date
        if detected_date.is_none() {
            if let Some(caps) = date_regex.captures(trimmed) {
                if let Some(m) = caps.get(1) {
                    detected_date = Some(m.as_str().to_string());
                }
            }
        }

        // Check for amount
        let lower = trimmed.to_lowercase();
        if lower.contains("total") || lower.contains("amount") || lower.contains("due") || lower.contains("$") || lower.contains("¥") {
            if let Some(caps) = amount_regex.captures(&lower) {
                if let Some(m) = caps.get(1) {
                    let num_str: String = m.as_str().chars().filter(|c| c.is_digit(10) || *c == '.').collect();
                    if let Ok(val) = num_str.parse::<f64>() {
                        total_amount = Some(val);
                    }
                }
            }
        } else if idx > 0 {
            line_items.push(trimmed.to_string());
        }
    }

    if merchant.is_empty() {
        merchant = "Unknown Merchant".to_string();
    }

    let doc_type = if total_amount.is_some() { "receipt_or_invoice" } else { "document_note" };
    let task_desc = match total_amount {
        Some(amt) => format!("Review and archive {} invoice for ${:.2}", merchant, amt),
        None => format!("Archive document notes from {}", merchant),
    };

    let mem_summary = format!(
        "Physical document captured from {} on {}: total amount={:?}, {} items noted.",
        merchant,
        detected_date.as_deref().unwrap_or("today"),
        total_amount,
        line_items.len()
    );

    let doc = DocumentEntity {
        doc_type: doc_type.to_string(),
        merchant_or_title: merchant,
        total_amount,
        detected_date,
        line_items,
        suggested_task: task_desc,
        memory_representation: mem_summary,
    };

    serde_json::to_string(&doc).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_visual_scene_ocr_target_matching() {
        let scene_json = serde_json::json!({
            "screen_width": 1080,
            "screen_height": 2400,
            "elements": [
                {
                    "id": "header_txt",
                    "text": "Order Confirmation",
                    "bounds": [100.0, 200.0, 500.0, 80.0],
                    "confidence": 0.99,
                    "element_type": "text"
                },
                {
                    "id": "amount_txt",
                    "text": "Total: $128.50",
                    "bounds": [100.0, 600.0, 400.0, 60.0],
                    "confidence": 0.98,
                    "element_type": "text"
                },
                {
                    "id": "btn_pay",
                    "text": "Pay Now",
                    "bounds": [140.0, 1800.0, 800.0, 120.0],
                    "confidence": 0.99,
                    "element_type": "button"
                }
            ]
        }).to_string();

        let res_json = analyze_visual_scene(&scene_json, "pay now").expect("Must analyze");
        let res: VisualAnalysisResult = serde_json::from_str(&res_json).expect("Must parse JSON");
        assert!(res.matched);
        assert_eq!(res.target_id, "btn_pay");
        assert_eq!(res.action_type, "click");
        assert_eq!(res.tap_x, 540.0); // 140 + 800/2
        assert_eq!(res.tap_y, 1860.0); // 1800 + 120/2
        assert_eq!(res.currency_values, vec!["$128.50"]);
    }

    #[test]
    fn test_visual_document_invoice_extraction() {
        let ocr_tokens = serde_json::json!([
            { "id": "tok_1", "text": "Acme Hardware Store", "bounds": [50.0, 50.0, 300.0, 40.0] },
            { "id": "tok_2", "text": "Date: 2026-10-23", "bounds": [50.0, 100.0, 200.0, 30.0] },
            { "id": "tok_3", "text": "1x USB-C Cable", "bounds": [50.0, 160.0, 250.0, 30.0] },
            { "id": "tok_4", "text": "Total Due: $24.99", "bounds": [50.0, 220.0, 280.0, 40.0] }
        ]).to_string();

        let doc_json = parse_visual_document(&ocr_tokens).expect("Must parse document");
        let doc: DocumentEntity = serde_json::from_str(&doc_json).expect("Valid JSON");
        assert_eq!(doc.merchant_or_title, "Acme Hardware Store");
        assert_eq!(doc.total_amount, Some(24.99));
        assert_eq!(doc.detected_date.as_deref(), Some("2026-10-23"));
        assert!(doc.suggested_task.contains("Review and archive Acme Hardware Store invoice for $24.99"));
    }
}
