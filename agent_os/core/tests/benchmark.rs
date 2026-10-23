use agent_os_core::{redactor, slm, memory};
use std::time::Instant;

#[test]
fn benchmark_core_engines() {
    // 1. Benchmark Redactor (10,000 iterations)
    let sample_text = "用户联系人：张三 13812345678，邮箱：zhangsan@corp.com，绑定身份证：110101199003072345，银行卡：6222021234567890123，临时凭据：sk-abcdef1234567890xyz。请完成本地审计。";
    
    // Warmup
    for _ in 0..100 {
        let _ = redactor::redact(sample_text);
    }
    
    let redactor_start = Instant::now();
    let redactor_iters = 10_000;
    for _ in 0..redactor_iters {
        let res = redactor::redact(sample_text);
        assert_eq!(res.redactions_count, 5);
    }
    let redactor_duration = redactor_start.elapsed();
    let redactor_avg_us = (redactor_duration.as_nanos() as f64 / redactor_iters as f64) / 1000.0;
    println!("\n>>> [BENCHMARK] Redactor average latency: {:.3} μs ({:.4} ms)", redactor_avg_us, redactor_avg_us / 1000.0);

    // 2. Benchmark SLM Inference (10,000 iterations)
    let sample_goal = "查看本地便笺，然后发送系统通知，并将摘要导出分享";
    for _ in 0..100 {
        let _ = slm::infer_intent_logits(sample_goal, &[]);
    }

    let slm_start = Instant::now();
    let slm_iters = 10_000;
    for _ in 0..slm_iters {
        let logits = slm::infer_intent_logits(sample_goal, &[]);
        assert!(logits[slm::CLASS_NOTE] >= 0.50);
    }
    let slm_duration = slm_start.elapsed();
    let slm_avg_us = (slm_duration.as_nanos() as f64 / slm_iters as f64) / 1000.0;
    println!(">>> [BENCHMARK] Micro-SLM inference average latency: {:.3} μs ({:.4} ms)", slm_avg_us, slm_avg_us / 1000.0);

    // 3. Benchmark Associative Memory Selector (10,000 iterations across 20 memories)
    let mut mems = Vec::new();
    for i in 0..20 {
        mems.push(memory::MemoryRecord {
            id: format!("m-{}", i),
            kind: if i % 3 == 0 { memory::KIND_PREFERENCE.into() } else { memory::KIND_FACT.into() },
            content: format!("测试记忆条目 {}：包含便笺、日程以及各种日常系统操作与偏好记录。", i),
            source: memory::SOURCE_USER.into(),
            source_reference: "".into(),
            created_at: 1000000 - i * 10000,
            updated_at: 1000000 - i * 10000,
        });
    }

    let mem_start = Instant::now();
    let mem_iters = 10_000;
    for _ in 0..mem_iters {
        let top = memory::select_top_memories(sample_goal, &mems, 3, 1000000);
        assert_eq!(top.len(), 3);
    }
    let mem_duration = mem_start.elapsed();
    let mem_avg_us = (mem_duration.as_nanos() as f64 / mem_iters as f64) / 1000.0;
    println!(">>> [BENCHMARK] Associative Memory Selector (20 items) average latency: {:.3} μs ({:.4} ms)\n", mem_avg_us, mem_avg_us / 1000.0);

    // Assert strict SLAs
    assert!(redactor_avg_us < 200.0, "Redactor latency SLA violated: {} μs", redactor_avg_us);
    assert!(slm_avg_us < 50.0, "SLM inference latency SLA violated: {} μs", slm_avg_us);
    assert!(mem_avg_us < 150.0, "Memory selector latency SLA violated: {} μs", mem_avg_us);
}
