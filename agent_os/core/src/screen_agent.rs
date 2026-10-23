use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScreenNode {
    pub id: String,
    pub class_name: String,
    pub text: Option<String>,
    pub content_description: Option<String>,
    pub bounds: [i32; 4], // [left, top, right, bottom]
    pub is_clickable: bool,
    pub is_editable: bool,
    pub is_scrollable: bool,
    pub is_visible: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UiActionPlan {
    pub action_type: String, // "click", "set_text", "scroll_forward", "scroll_backward"
    pub target_x: i32,
    pub target_y: i32,
    pub target_node_id: Option<String>,
    pub text_payload: Option<String>,
    pub reason: String,
}

pub struct ScreenAgent;

impl ScreenAgent {
    /// Calculate center coordinate for tapping a node
    pub fn get_node_center(bounds: &[i32; 4]) -> (i32, i32) {
        let cx = (bounds[0] + bounds[2]) / 2;
        let cy = (bounds[1] + bounds[3]) / 2;
        (cx, cy)
    }

    /// Match a node based on search term in text, description, or id
    pub fn find_node<'a>(nodes: &'a [ScreenNode], query: &str) -> Option<&'a ScreenNode> {
        let q = query.trim().to_lowercase();
        if q.is_empty() {
            return None;
        }
        // 1. Direct exact or substring match in text
        for node in nodes {
            if let Some(ref text) = node.text {
                if text.to_lowercase().contains(&q) {
                    return Some(node);
                }
            }
        }
        // 2. Match in content description
        for node in nodes {
            if let Some(ref desc) = node.content_description {
                if desc.to_lowercase().contains(&q) {
                    return Some(node);
                }
            }
        }
        // 3. Match in resource id
        for node in nodes {
            if node.id.to_lowercase().contains(&q) {
                return Some(node);
            }
        }
        None
    }

    /// Analyze screen hierarchy and synthesize the next UI automation action
    pub fn plan_action(nodes: &[ScreenNode], goal: &str) -> Option<UiActionPlan> {
        let goal_lower = goal.to_lowercase();

        // Check if goal specifies clicking a particular button
        if goal_lower.contains("点击") || goal_lower.contains("click") || goal_lower.contains("tap") {
            let target_keyword = goal_lower
                .replace("点击", "")
                .replace("click", "")
                .replace("tap", "")
                .replace("按钮", "")
                .replace("button", "")
                .trim()
                .to_string();

            if !target_keyword.is_empty() {
                if let Some(node) = Self::find_node(nodes, &target_keyword) {
                    let (cx, cy) = Self::get_node_center(&node.bounds);
                    return Some(UiActionPlan {
                        action_type: "click".to_string(),
                        target_x: cx,
                        target_y: cy,
                        target_node_id: Some(node.id.clone()),
                        text_payload: None,
                        reason: format!("Located target node '{}' at ({}, {})", target_keyword, cx, cy),
                    });
                }
            }
        }

        // Check if goal specifies entering text
        if goal_lower.contains("输入") || goal_lower.contains("type") || goal_lower.contains("enter") {
            let text_to_enter = goal_lower
                .replace("输入", "")
                .replace("type", "")
                .replace("enter", "")
                .trim()
                .to_string();
            let payload = if text_to_enter.is_empty() { goal.to_string() } else { text_to_enter };

            for node in nodes {
                if node.is_editable {
                    let (cx, cy) = Self::get_node_center(&node.bounds);
                    return Some(UiActionPlan {
                        action_type: "set_text".to_string(),
                        target_x: cx,
                        target_y: cy,
                        target_node_id: Some(node.id.clone()),
                        text_payload: Some(payload),
                        reason: format!("Found editable input field at ({}, {})", cx, cy),
                    });
                }
            }
        }

        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_find_node_and_plan_click() {
        let nodes = vec![
            ScreenNode {
                id: "com.example.app:id/title".to_string(),
                class_name: "android.widget.TextView".to_string(),
                text: Some("确认订单".to_string()),
                content_description: None,
                bounds: [100, 200, 500, 300],
                is_clickable: false,
                is_editable: false,
                is_scrollable: false,
                is_visible: true,
            },
            ScreenNode {
                id: "com.example.app:id/submit_btn".to_string(),
                class_name: "android.widget.Button".to_string(),
                text: Some("立即支付".to_string()),
                content_description: Some("Submit Payment".to_string()),
                bounds: [200, 800, 800, 950],
                is_clickable: true,
                is_editable: false,
                is_scrollable: false,
                is_visible: true,
            },
        ];

        let plan = ScreenAgent::plan_action(&nodes, "点击立即支付").expect("Should find button");
        assert_eq!(plan.action_type, "click");
        assert_eq!(plan.target_x, 500);
        assert_eq!(plan.target_y, 875);
        assert_eq!(plan.target_node_id.as_deref(), Some("com.example.app:id/submit_btn"));
    }
}
