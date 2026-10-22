package com.scheffsblend.flckvsn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

interface Detector {
    val spec: ModelSpec
    val isGpuAccelerated: Boolean
    val processor: String get() = if (isGpuAccelerated) "GPU" else "CPU"
    fun detect(bitmap: Bitmap): List<Detection>
    fun close()
}

/** Loads a .tflite file straight out of assets, mmap'd (no copy). */
private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
    val afd = context.assets.openFd(assetPath)
    FileInputStream(afd.fileDescriptor).use { input ->
        return input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }
}

private fun bitmapToInputBuffer(bitmap: Bitmap, normalize: (Int) -> Float): ByteBuffer {
    val w = bitmap.width
    val h = bitmap.height
    val buffer = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (px in pixels) {
        buffer.putFloat(normalize((px shr 16) and 0xFF)) // R
        buffer.putFloat(normalize((px shr 8) and 0xFF))  // G
        buffer.putFloat(normalize(px and 0xFF))           // B
    }
    buffer.rewind()
    return buffer
}

private data class InterpreterHolder(
    val interpreter: InterpreterApi,
    val gpuDelegate: GpuDelegate?
)

private fun createInterpreter(modelBuffer: MappedByteBuffer, tag: String): InterpreterHolder {
    var delegate: GpuDelegate? = null
    var interpreter: InterpreterApi?

    try {
        delegate = GpuDelegate()
        val options = InterpreterApi.Options().apply {
            addDelegate(delegate)
        }
        interpreter = InterpreterApi.create(modelBuffer, options)
        Log.i(tag, "Successfully initialized TFLite interpreter with GPU delegate.")
    } catch (e: Throwable) {
        Log.w(tag, "GPU delegate initialization failed, falling back to CPU", e)
        delegate?.close()
        delegate = null
        interpreter = null
    }

    if (interpreter == null) {
        val options = InterpreterApi.Options().apply {
            setNumThreads(4)
        }
        interpreter = InterpreterApi.create(modelBuffer, options)
        Log.i(tag, "Initialized TFLite interpreter with CPU (XNNPACK).")
    }

    return InterpreterHolder(interpreter, delegate)
}

/**
 * SSD (MobileNet-SSD-style) detector.
 *
 * Preprocessing: "squish" (direct, aspect-ignoring stretch) to inputSize,
 * normalized to [-1, 1] (standard TF SSD-MobileNet preprocessing).
 *
 * Postprocessing: anchor-box decode using the real TF Object Detection
 * API SSD encoding (y/x/h/w order, scale factors 10/10/5/5 -- these exact
 * scale factors come from Flock's own models.json and were not altered).
 * `class_predictions` is already post-sigmoid in the exported graph (its
 * raw values are bounded to [0,1], confirmed empirically) -- do NOT apply
 * sigmoid again, that was an early bug caught by cross-checking against
 * the YOLO models on the same photo (it produced thousands of spurious
 * ~0.5-confidence boxes).
 */
class SsdDetector(context: Context, override val spec: ModelSpec) : Detector {
    private val interpreter: InterpreterApi
    private val gpuDelegate: GpuDelegate?
    override val isGpuAccelerated: Boolean get() = gpuDelegate != null
    private val anchors: FloatArray // flattened [x0,y0,x1,y1] per anchor
    private val boxOutputIndex: Int
    private val classOutputIndex: Int

    init {
        val holder = createInterpreter(loadModelFile(context, spec.assetFile), "SsdDetector")
        interpreter = holder.interpreter
        gpuDelegate = holder.gpuDelegate

        // Bind by tensor name rather than assuming output index order --
        // matches how this was validated against the Python prototype.
        var boxIdx = -1
        var classIdx = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val name = interpreter.getOutputTensor(i).name()
            if (name.contains("box_encodings")) boxIdx = i
            if (name.contains("class_predictions")) classIdx = i
        }
        check(boxIdx >= 0 && classIdx >= 0) { "Unexpected SSD model output tensors for ${spec.assetFile}" }
        boxOutputIndex = boxIdx
        classOutputIndex = classIdx

        val json = context.assets.open("flock_models/anchors.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("anchors")
        anchors = FloatArray(arr.length() * 4)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            anchors[i * 4 + 0] = a.getDouble("x0").toFloat()
            anchors[i * 4 + 1] = a.getDouble("y0").toFloat()
            anchors[i * 4 + 2] = a.getDouble("x1").toFloat()
            anchors[i * 4 + 3] = a.getDouble("y1").toFloat()
        }
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val resized = ImageUtils.squish(bitmap, spec.inputSize)
        val input = bitmapToInputBuffer(resized) { v -> v / 127.5f - 1.0f }

        val numAnchors = anchors.size / 4
        val numClasses = spec.labels.size + 1 // + background
        val boxOut = Array(1) { Array(numAnchors) { FloatArray(4) } }
        val classOut = Array(1) { Array(numAnchors) { FloatArray(numClasses) } }

        val outputs = HashMap<Int, Any>()
        outputs[boxOutputIndex] = boxOut
        outputs[classOutputIndex] = classOut
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val dets = ArrayList<Detection>()
        for (i in 0 until numAnchors) {
            val ax0 = anchors[i * 4 + 0]
            val ay0 = anchors[i * 4 + 1]
            val ax1 = anchors[i * 4 + 2]
            val ay1 = anchors[i * 4 + 3]
            val aw = ax1 - ax0
            val ah = ay1 - ay0
            val acx = (ax0 + ax1) / 2f
            val acy = (ay0 + ay1) / 2f

            val box = boxOut[0][i]
            val ty = box[0]
            val tx = box[1]
            val th = box[2]
            val tw = box[3]
            val cy = ty / 10f * ah + acy
            val cx = tx / 10f * aw + acx
            val h = exp(th / 5f) * ah
            val w = exp(tw / 5f) * aw
            val left = cx - w / 2f
            val top = cy - h / 2f
            val right = cx + w / 2f
            val bottom = cy + h / 2f

            val scores = classOut[0][i]
            for (clsIdx in 1 until numClasses) { // skip background=0
                val sc = scores[clsIdx]
                if (sc >= spec.confidenceThreshold) {
                    dets.add(Detection(spec.labels[clsIdx - 1], sc, left, top, right, bottom))
                }
            }
        }
        return ImageUtils.nms(dets, spec.clusterThreshold)
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}

/**
 * YOLOv5 detector (covers pico3/nano2/large -- same output layout, only
 * input size / class count / thresholds differ).
 *
 * Preprocessing: letterbox (aspect-preserving resize + 114-gray pad) to
 * inputSize x inputSize, normalized to [0, 1].
 *
 * Postprocessing: output row = [cx, cy, w, h, objectness, cls0..clsN],
 * all already normalized/activated in the exported graph (no extra
 * sigmoid needed -- standard Ultralytics TFLite export behavior,
 * confirmed empirically: raw values are already well-formed
 * probabilities and decoded boxes land exactly on real objects). Box
 * decode undoes the letterbox transform to map back to original-frame
 * normalized coordinates.
 */
class YoloDetector(context: Context, override val spec: ModelSpec) : Detector {
    private val interpreter: InterpreterApi
    private val gpuDelegate: GpuDelegate?
    override val isGpuAccelerated: Boolean get() = gpuDelegate != null
    private val numClasses = spec.labels.size
    private val numBoxes: Int
    private val rowLen: Int

    init {
        val holder = createInterpreter(loadModelFile(context, spec.assetFile), "YoloDetector")
        interpreter = holder.interpreter
        gpuDelegate = holder.gpuDelegate
        val outputShape = interpreter.getOutputTensor(0).shape() // [1, N, 5+numClasses]
        numBoxes = outputShape[1]
        rowLen = outputShape[2]
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val lb = ImageUtils.letterbox(bitmap, spec.inputSize)
        val input = bitmapToInputBuffer(lb.bitmap) { v -> v / 255f }

        val output = Array(1) { Array(numBoxes) { FloatArray(rowLen) } }
        interpreter.run(input, output)

        val dets = ArrayList<Detection>()
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        for (i in 0 until numBoxes) {
            val row = output[0][i]
            val cx = row[0]
            val cy = row[1]
            val w = row[2]
            val h = row[3]
            val obj = row[4]

            var bestIdx = 0
            var bestScore = row[5]
            for (c in 1 until numClasses) {
                if (row[5 + c] > bestScore) {
                    bestScore = row[5 + c]
                    bestIdx = c
                }
            }
            val score = obj * bestScore
            if (score < spec.confidenceThreshold) continue

            // box in letterboxed-input pixel space
            var left = (cx - w / 2f) * spec.inputSize
            var top = (cy - h / 2f) * spec.inputSize
            var right = (cx + w / 2f) * spec.inputSize
            var bottom = (cy + h / 2f) * spec.inputSize
            // undo letterbox -> original-image pixel space -> normalized
            left = (left - lb.padX) / lb.scale
            top = (top - lb.padY) / lb.scale
            right = (right - lb.padX) / lb.scale
            bottom = (bottom - lb.padY) / lb.scale

            dets.add(
                Detection(
                    label = spec.labels[bestIdx],
                    score = score,
                    left = left / origW,
                    top = top / origH,
                    right = right / origW,
                    bottom = bottom / origH,
                ),
            )
        }
        return ImageUtils.nms(dets, spec.clusterThreshold)
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}

object DetectorFactory {
    fun create(context: Context, spec: ModelSpec): Detector = when (spec.type) {
        ModelType.SSD -> SsdDetector(context, spec)
        ModelType.YOLO -> YoloDetector(context, spec)
    }
}
