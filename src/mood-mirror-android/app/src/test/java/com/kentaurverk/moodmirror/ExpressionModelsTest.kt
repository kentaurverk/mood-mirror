package com.kentaurverk.moodmirror

import com.kentaurverk.moodmirror.domain.ExpressionLabel
import com.kentaurverk.moodmirror.domain.ExpressionScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionModelsTest {
    @Test
    fun expressionUiOrderMatchesProductContract() {
        assertEquals(
            listOf("HAPPY", "SURPRISED", "SAD", "ANGRY", "DISGUSTED", "FEARFUL", "NEUTRAL"),
            ExpressionLabel.entries.map { it.name },
        )
    }

    @Test
    fun smoothingUses52PercentPreviousAnd48PercentCurrent() {
        val previous = ExpressionScores(ExpressionLabel.entries.associateWith { if (it == ExpressionLabel.NEUTRAL) 1f else 0f })
        val current = ExpressionScores(ExpressionLabel.entries.associateWith { if (it == ExpressionLabel.HAPPY) 1f else 0f })
        val result = previous.blend(current)
        assertEquals(0.48f, result[ExpressionLabel.HAPPY], 0.0001f)
        assertEquals(0.52f, result[ExpressionLabel.NEUTRAL], 0.0001f)
        assertTrue(result.values.values.sum() > 0.99f)
    }
}
