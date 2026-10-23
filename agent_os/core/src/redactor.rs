use std::sync::OnceLock;
use regex::Regex;

#[derive(Debug, Clone, PartialEq)]
pub struct RedactionResult {
    pub redacted_text: String,
    pub redactions_count: usize,
}

impl RedactionResult {
    pub fn has_redactions(&self) -> bool {
        self.redactions_count > 0
    }
}

static PHONE_REGEX: OnceLock<Regex> = OnceLock::new();
static EMAIL_REGEX: OnceLock<Regex> = OnceLock::new();
static SECRET_REGEX: OnceLock<Regex> = OnceLock::new();
static ID_CARD_REGEX: OnceLock<Regex> = OnceLock::new();
static BANK_CARD_REGEX: OnceLock<Regex> = OnceLock::new();

fn get_phone_regex() -> &'static Regex {
    PHONE_REGEX.get_or_init(|| {
        Regex::new(r"(?m)(^|[^\d])(?:\+?86\s*)?(1[3-9]\d)\s*(\d{4})\s*(\d{4})([^\d]|$)").unwrap()
    })
}

fn get_email_regex() -> &'static Regex {
    EMAIL_REGEX.get_or_init(|| {
        Regex::new(r"([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})").unwrap()
    })
}

fn get_secret_regex() -> &'static Regex {
    SECRET_REGEX.get_or_init(|| {
        Regex::new(r"(?i)(sk-[a-zA-Z0-9_\-]{16,}|Bearer\s+[a-zA-Z0-9_\-\.]{16,}|api[_-]?key[\x22'\s:=]+[a-zA-Z0-9_\-]{16,})").unwrap()
    })
}

fn get_id_card_regex() -> &'static Regex {
    ID_CARD_REGEX.get_or_init(|| {
        Regex::new(r"(?m)(^|[^\d])([1-9]\d{3})\d{10}(\d{3}[0-9Xx])([^\d]|$)").unwrap()
    })
}

fn get_bank_card_regex() -> &'static Regex {
    BANK_CARD_REGEX.get_or_init(|| {
        Regex::new(r"(?m)(^|[^\d])(62\d{2}|\d{4})\s*(?:\d{4})\s*(?:\d{4,7})\s*(\d{4})([^\d]|$)").unwrap()
    })
}

/// Redacts sensitive PII across 5 categories in pure native Rust with zero GC.
pub fn redact(input: &str) -> RedactionResult {
    if input.is_empty() {
        return RedactionResult {
            redacted_text: String::new(),
            redactions_count: 0,
        };
    }

    let mut count = 0;
    let mut current = input.to_string();

    // 1. Phone numbers
    let phone_re = get_phone_regex();
    let phone_matches = phone_re.find_iter(&current).count();
    if phone_matches > 0 {
        count += phone_matches;
        current = phone_re.replace_all(&current, "${1}${2}****${4}${5}").to_string();
    }

    // 2. Email addresses
    let email_re = get_email_regex();
    let email_matches = email_re.find_iter(&current).count();
    if email_matches > 0 {
        count += email_matches;
        current = email_re.replace_all(&current, "${1}***@${2}").to_string();
    }

    // 3. API secrets / Bearer tokens
    let secret_re = get_secret_regex();
    let secret_matches = secret_re.find_iter(&current).count();
    if secret_matches > 0 {
        count += secret_matches;
        current = secret_re.replace_all(&current, "[REDACTED_SECRET]").to_string();
    }

    // 4. Resident Identity Cards (18 digits)
    let id_re = get_id_card_regex();
    let id_matches = id_re.find_iter(&current).count();
    if id_matches > 0 {
        count += id_matches;
        current = id_re.replace_all(&current, "${1}${2}**********${3}${4}").to_string();
    }

    // 5. Bank cards (16-19 digits)
    let bank_re = get_bank_card_regex();
    let bank_matches = bank_re.find_iter(&current).count();
    if bank_matches > 0 {
        count += bank_matches;
        current = bank_re.replace_all(&current, "${1}${2}********${3}${4}").to_string();
    }

    RedactionResult {
        redacted_text: current,
        redactions_count: count,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_phone_redaction() {
        let res = redact("联系人电话是 13812345678 请妥善保管");
        assert_eq!(res.redacted_text, "联系人电话是 138****5678 请妥善保管");
        assert_eq!(res.redactions_count, 1);
    }

    #[test]
    fn test_email_redaction() {
        let res = redact("发送邮件到 alice_work@company.org 进行验证");
        assert_eq!(res.redacted_text, "发送邮件到 a***@company.org 进行验证");
        assert_eq!(res.redactions_count, 1);
    }

    #[test]
    fn test_secret_redaction() {
        let res = redact("请使用密钥 sk-abcdef1234567890xyz 进行连接");
        assert_eq!(res.redacted_text, "请使用密钥 [REDACTED_SECRET] 进行连接");
        assert_eq!(res.redactions_count, 1);
    }

    #[test]
    fn test_id_and_bank_card() {
        let res = redact("身份证 110101199003072345 绑定卡号 6222021234567890123");
        assert_eq!(res.redacted_text, "身份证 1101**********2345 绑定卡号 6222********0123");
        assert_eq!(res.redactions_count, 2);
    }

    #[test]
    fn test_clean_text() {
        let res = redact("这是一个完全没有个人隐私的公开任务描述。");
        assert_eq!(res.redacted_text, "这是一个完全没有个人隐私的公开任务描述。");
        assert_eq!(res.redactions_count, 0);
        assert!(!res.has_redactions());
    }
}
