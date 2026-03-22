package com.image_classification_app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaseOptimiseTest {

    @Test
    fun isBenchmarkIntent_trueWhenActionMatches() {
        val intent = Intent(MaseOptimise.ACTION_BENCHMARK)
        assertTrue(MaseOptimise.isBenchmarkIntent(intent))
    }

    @Test
    fun isBenchmarkIntent_falseForMainOrNull() {
        assertFalse(MaseOptimise.isBenchmarkIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(MaseOptimise.isBenchmarkIntent(null))
    }
}
