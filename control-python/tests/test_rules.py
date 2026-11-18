"""The rule engine: threshold -> quorum -> cooldown per (rule, device)."""
from datetime import datetime, timedelta, timezone

from sqlalchemy import insert

from ar_control.db import alert, alert_rule
from ar_control.rules import Hit, RuleEngine

T0 = datetime(2026, 11, 17, 12, 0, 0, tzinfo=timezone.utc)


def add_rule(engine, **kw):
    row = {"name": "cows", "label": "cow", "min_confidence": 0.5, "min_count": 1, "cooldown_seconds": 10,
           "severity": "high", "device_id": None, "enabled": True, **kw}
    with engine.begin() as c:
        return c.execute(insert(alert_rule).values(**row).returning(alert_rule.c.id)).scalar()


def run(engine, rules, hits, device="dev-a", at=T0, frame_id=1, commit=True):
    rules.begin_batch()
    with engine.begin() as c:
        raised = rules.evaluate(c, device, at, "s1", frame_id, hits)
        if raised:
            c.execute(insert(alert), raised)
    if commit:
        rules.commit_batch()
    return raised


def test_threshold(engine):
    add_rule(engine, min_confidence=0.6)
    rules = RuleEngine(0)
    assert run(engine, rules, [Hit("cow", 0.59)]) == []
    assert len(run(engine, rules, [Hit("cow", 0.6)])) == 1


def test_quorum_counts_only_confident_hits_in_one_frame(engine):
    add_rule(engine, min_count=3, min_confidence=0.5)
    rules = RuleEngine(0)
    assert run(engine, rules, [Hit("cow", 0.9), Hit("cow", 0.8), Hit("cow", 0.3)]) == []
    raised = run(engine, rules, [Hit("cow", 0.9), Hit("cow", 0.8), Hit("cow", 0.7)])
    assert len(raised) == 1 and raised[0]["count"] == 3 and abs(raised[0]["max_confidence"] - 0.9) < 1e-9


def test_cooldown_per_device(engine):
    add_rule(engine, cooldown_seconds=10)
    rules = RuleEngine(0)
    assert len(run(engine, rules, [Hit("cow", 0.9)], device="dev-a", at=T0)) == 1
    assert run(engine, rules, [Hit("cow", 0.9)], device="dev-a", at=T0 + timedelta(seconds=9)) == []
    # another pair of glasses is another event
    assert len(run(engine, rules, [Hit("cow", 0.9)], device="dev-b", at=T0 + timedelta(seconds=9))) == 1
    assert len(run(engine, rules, [Hit("cow", 0.9)], device="dev-a", at=T0 + timedelta(seconds=10))) == 1


def test_label_matching_is_case_insensitive(engine):
    add_rule(engine, label="cow")
    assert len(run(engine, RuleEngine(0), [Hit("Cow", 0.9)])) == 1


def test_device_specific_rule_and_disabled_rule(engine):
    add_rule(engine, name="only-b", device_id="dev-b")
    add_rule(engine, name="off", enabled=False)
    rules = RuleEngine(0)
    assert run(engine, rules, [Hit("cow", 0.9)], device="dev-a") == []
    assert [a["rule_name"] for a in run(engine, rules, [Hit("cow", 0.9)], device="dev-b")] == ["only-b"]


def test_cold_start_reads_the_last_alert_from_the_database(engine):
    add_rule(engine, cooldown_seconds=60)
    run(engine, RuleEngine(0), [Hit("cow", 0.9)], at=T0)
    # a NEW engine = a restarted consumer: must not re-announce within the cooldown
    fresh = RuleEngine(0)
    assert run(engine, fresh, [Hit("cow", 0.9)], at=T0 + timedelta(seconds=30)) == []
    assert len(run(engine, fresh, [Hit("cow", 0.9)], at=T0 + timedelta(seconds=61))) == 1


def test_frames_in_the_same_batch_respect_the_cooldown(engine):
    add_rule(engine, cooldown_seconds=10)
    rules = RuleEngine(0)
    rules.begin_batch()
    with engine.begin() as c:
        first = rules.evaluate(c, "dev-a", T0, "s1", 1, [Hit("cow", 0.9)])
        second = rules.evaluate(c, "dev-a", T0 + timedelta(seconds=1), "s1", 2, [Hit("cow", 0.9)])
    rules.commit_batch()
    assert len(first) == 1 and second == []


def test_rolled_back_batch_does_not_count(engine):
    add_rule(engine, cooldown_seconds=10)
    rules = RuleEngine(0)
    rules.begin_batch()
    with engine.begin() as c:
        assert len(rules.evaluate(c, "dev-a", T0, "s1", 1, [Hit("cow", 0.9)])) == 1
    rules.rollback_batch()     # the transaction failed: that alert was never stored
    assert len(run(engine, rules, [Hit("cow", 0.9)], at=T0 + timedelta(seconds=1))) == 1


def test_backlog_uses_frame_time_not_processing_time(engine):
    """After an outage the consumer processes old frames quickly: alerts follow FRAME time."""
    add_rule(engine, cooldown_seconds=10)
    rules = RuleEngine(0)
    raised = sum(len(run(engine, rules, [Hit("cow", 0.9)], at=T0 + timedelta(seconds=s), frame_id=s))
                 for s in range(0, 30))      # 30 s of frames, processed in a blink
    assert raised == 3                       # t=0, t=10, t=20


def test_contains_rule_covers_every_blight(engine):
    """Step 7: one rule for all blights, whatever the crop (plant-disease model labels)."""
    add_rule(engine, name="any-blight", label="blight", match="contains", min_confidence=0.5, cooldown_seconds=0)
    rules = RuleEngine(0)
    for i, lab in enumerate(["Corn leaf blight", "Tomato leaf late blight", "Potato leaf early blight"]):
        raised = run(engine, rules, [Hit(lab, 0.9)], at=T0 + timedelta(seconds=i), frame_id=i)
        assert len(raised) == 1 and raised[0]["label"] == lab      # the label the model emitted
    assert run(engine, rules, [Hit("Tomato leaf", 0.95)], at=T0 + timedelta(seconds=9), frame_id=9) == []
    assert run(engine, rules, [Hit("Corn rust leaf", 0.95)], at=T0 + timedelta(seconds=10), frame_id=10) == []


def test_contains_counts_all_matching_labels_and_reports_the_most_confident(engine):
    add_rule(engine, name="any-blight", label="blight", match="contains", min_count=2, cooldown_seconds=0)
    raised = run(engine, RuleEngine(0), [Hit("Corn leaf blight", 0.6), Hit("Corn Gray leaf spot", 0.9),
                                         Hit("Potato leaf late blight", 0.8)])
    assert len(raised) == 1 and raised[0]["count"] == 2
    assert raised[0]["label"] == "Potato leaf late blight" and abs(raised[0]["max_confidence"] - 0.8) < 1e-9


def test_exact_rule_does_not_match_a_longer_label(engine):
    add_rule(engine, name="exact-blight", label="blight", match="exact")
    assert run(engine, RuleEngine(0), [Hit("Corn leaf blight", 0.99)]) == []
