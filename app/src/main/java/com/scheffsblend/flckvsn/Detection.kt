package com.scheffsblend.flckvsn

/**
 * A single detection, in coordinates normalized to the *original* camera
 * frame (0f..1f on both axes, independent of model input size or any
 * letterbox/squish transform -- those are undone before this is built).
 */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
