package com.scheffsblend.flckvsn

enum class ModelType { SSD, YOLO }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val assetFile: String,
    val type: ModelType,
    val inputSize: Int, // square input, e.g. 320 or 640 (300 for the SSD model)
    val labels: List<String>, // class-index order, NOT including SSD's background class
    val confidenceThreshold: Float,
    val clusterThreshold: Float, // NMS IoU threshold
)

object ModelCatalog {
    /** SSD (MobileNet-SSD-style), input 300x300, 10 classes + implicit background. */
    val SSD_SMALL = ModelSpec(
        id = "ssd_small",
        displayName = "SSD small (flock_small)",
        assetFile = "flock_models/flock_small_tf.tflite",
        type = ModelType.SSD,
        inputSize = 300,
        labels = listOf(
            "bicycle", "bus", "car", "cat", "dog", "licensePlate",
            "motorcycle", "person", "truck", "trailer",
        ),
        confidenceThreshold = 0.5f,
        clusterThreshold = 0.45f, // not specified by Flock for this model; standard default used
    )

    /** YOLOv5, input 320x320, 4 classes (vehicles collapsed into one "vehicle" class). */
    val YOLO_PICO3 = ModelSpec(
        id = "yolo_pico3",
        displayName = "YOLO pico3 (default on real camera)",
        assetFile = "flock_models/MLM-2854-pico3-best-fp16.tflite",
        type = ModelType.YOLO,
        inputSize = 320,
        labels = listOf("bicycle", "licensePlate", "person", "vehicle"),
        confidenceThreshold = 0.35f,
        clusterThreshold = 0.45f,
    )

    /** YOLOv5, input 320x320, 8 classes (vehicles split out: bus/car/truck/trailer). */
    val YOLO_NANO2 = ModelSpec(
        id = "yolo_nano2",
        displayName = "YOLO nano2 (most classes)",
        assetFile = "flock_models/MLM-2324-nano2-fp16.tflite",
        type = ModelType.YOLO,
        inputSize = 320,
        labels = listOf(
            "bicycle", "bus", "car", "licensePlate", "motorcycle", "person", "truck", "trailer",
        ),
        confidenceThreshold = 0.4f,
        clusterThreshold = 0.45f,
    )

    /** YOLOv5, input 640x640, 4 classes, ~92MB -- largest/most accurate model. */
    val YOLO_LARGE = ModelSpec(
        id = "yolo_large",
        displayName = "YOLO large (biggest, slowest)",
        assetFile = "flock_models/MLM-2857-large-fp16.tflite",
        type = ModelType.YOLO,
        inputSize = 640,
        labels = listOf("bicycle", "vehicle", "person", "trailer"),
        confidenceThreshold = 0.4f,
        clusterThreshold = 0.45f,
    )

    val ALL = listOf(YOLO_PICO3, YOLO_NANO2, YOLO_LARGE, SSD_SMALL)
}
