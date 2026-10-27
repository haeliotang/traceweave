use agent_os_core::causal_memory::{merge, resolve, Clock, Version};

fn version(entries: &[(&str, u64)], text: Option<&str>) -> Version {
    Version {
        clock: Clock(entries.iter().map(|(name, counter)| ((*name).to_string(), *counter)).collect()),
        value: text.map(str::to_string),
    }
}

#[test]
fn concurrent_edits_remain_explicit_conflicts_until_user_selection() {
    let a = version(&[("a", 2)], Some("left edit"));
    let b = version(&[("a", 1), ("b", 1)], Some("right edit"));
    let choices = merge(&[a.clone()], &[b.clone()]).unwrap();
    assert_eq!(choices.len(), 2);
    assert_eq!(choices, merge(&[b.clone()], &[a.clone()]).unwrap());
    let selected = choices.iter().position(|item| item.value.as_deref() == Some("left edit")).unwrap();
    let resolved = resolve(&choices, selected, "a").unwrap();
    assert_eq!(resolved.value.as_deref(), Some("left edit"));
    assert_eq!(merge(&[resolved.clone()], &choices).unwrap(), vec![resolved]);
}

#[test]
fn old_updates_and_duplicate_delivery_do_not_change_a_newer_value() {
    let old = version(&[("a", 1)], Some("old"));
    let new = version(&[("a", 2)], Some("new"));
    assert_eq!(merge(&[new.clone()], &[old, new.clone()]).unwrap(), vec![new]);
}

#[test]
fn deletion_dominates_offline_updates_and_cannot_be_resolved_as_an_edit() {
    let deleted = version(&[("a", 2)], None);
    let offline = version(&[("a", 1), ("b", 99)], Some("offline edit"));
    let merged = merge(&[deleted.clone()], &[offline]).unwrap();
    assert_eq!(merged, vec![deleted.clone()]);
    let resolution = resolve(&merged, 0, "b").unwrap();
    assert!(resolution.value.is_none());
    assert_eq!(merge(&[resolution.clone()], &[deleted]).unwrap(), vec![resolution]);
}

#[test]
fn conflicting_payloads_with_identical_clock_are_rejected() {
    let a = version(&[("a", 1)], Some("left"));
    let b = version(&[("a", 1)], Some("right"));
    assert!(merge(&[a], &[b]).is_err());
}

#[test]
fn invalid_clocks_and_invalid_selection_are_rejected() {
    assert!(merge(&[version(&[("", 1)], Some("bad"))], &[]).is_err());
    assert!(merge(&[version(&[("a", 0)], Some("bad"))], &[]).is_err());
    assert!(resolve(&[version(&[("a", 1)], Some("ok"))], 1, "b").is_err());
    assert!(resolve(&[version(&[("a", u64::MAX)], Some("ok"))], 0, "a").is_err());
    assert!(merge(&vec![version(&[("a", 1)], Some("ok")); 65], &[]).is_err());
}

#[test]
fn two_replicas_converge_after_user_resolves_offline_conflict() {
    let base = version(&[("a", 1)], Some("original"));
    let left = version(&[("a", 2)], Some("left edit"));
    let right = version(&[("a", 1), ("b", 1)], Some("right edit"));
    let left_choices = merge(&[left], &[right.clone()]).unwrap();
    let right_choices = merge(&[right], &left_choices).unwrap();
    assert_eq!(left_choices, right_choices);
    let index = left_choices.iter().position(|item| item.value.as_deref() == Some("right edit")).unwrap();
    let selected = resolve(&left_choices, index, "a").unwrap();
    let left_final = merge(&left_choices, &[selected.clone()]).unwrap();
    let right_final = merge(&right_choices, &[selected.clone(), base]).unwrap();
    assert_eq!(left_final, vec![selected]);
    assert_eq!(left_final, right_final);
}
