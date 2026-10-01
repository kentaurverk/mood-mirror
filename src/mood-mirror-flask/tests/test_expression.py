import pytest

from expression import LABELS, MODEL_LABELS, softmax_scores


def test_scores_are_normalized_and_in_ui_order():
    scores = softmax_scores([0.0] * len(MODEL_LABELS))
    assert tuple(scores) == LABELS
    assert sum(scores.values()) == pytest.approx(1.0, abs=0.001)


def test_highest_logit_becomes_highest_expression():
    logits = [0.0] * len(MODEL_LABELS)
    logits[MODEL_LABELS.index("happy")] = 4.0
    scores = softmax_scores(logits)
    assert max(scores, key=scores.get) == "happy"
    assert scores["happy"] > 0.8


def test_model_output_size_is_checked():
    with pytest.raises(ValueError, match="Expected 7"):
        softmax_scores([0.0] * 6)
