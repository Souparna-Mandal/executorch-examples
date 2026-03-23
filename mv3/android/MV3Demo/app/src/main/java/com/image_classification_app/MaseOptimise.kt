package com.image_classification_app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import android.content.Intent
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import java.io.File

/**
 * Optuna / adb automation: eval under [ComponentActivity.getFilesDir]; metrics JSON is written to the
 * same private [filesDir] (host reads via `adb exec-out run-as <pkg> cat files/<name>` on debuggable builds).
 *
 * - **train / test** (ImageFolder): `files/<datasetSubdir>/<split>/<wnidOrClassDir>/**/*.{jpg,jpeg,png,webp}`
 *   — class index = lexicographic order of immediate subdirs (PyTorch `ImageFolder`).
 * - **val** (Tiny ImageNet): `files/<datasetSubdir>/val/val_annotations.txt` plus images under
 *   `val/images/` (or `val/`). Each line: `filename<TAB>wnid<TAB>...`. Wnid → index matches
 *   sorted folder names under `train/` when present; otherwise sorted unique wnids from the file.
 */
object MaseOptimise {

    const val ACTION_BENCHMARK = "com.image_classification_app.action.BENCHMARK"

    const val EXTRA_SPLIT = "split"
    const val EXTRA_TRIAL_ID = "trial_id"
    const val EXTRA_MODEL_NAME = "model_name"
    const val EXTRA_DATASET_SUBDIR = "dataset_subdir"
    const val EXTRA_OUT_NAME = "out_name"

    private const val TAG = "MaseOptimise"
    private const val VAL_ANNOTATIONS_FILE = "val_annotations.txt"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** Log first N samples at DEBUG; then every [every]th sample (avoid logcat flood on full val). */
    private const val BENCHMARK_LOG_FIRST = 32
    private const val BENCHMARK_LOG_EVERY = 100

    fun isBenchmarkIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_BENCHMARK

    /**
     * Runs synchronously on the caller thread (use a background dispatcher from the activity).
     */
    fun runBenchmark(activity: ComponentActivity, intent: Intent) {
        val filesDir = activity.filesDir
        val splitRaw = intent.getStringExtra(EXTRA_SPLIT)?.lowercase()
        val trialId = intent.getStringExtra(EXTRA_TRIAL_ID) ?: "unknown"
        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "model.pte"
        val datasetSubdir = intent.getStringExtra(EXTRA_DATASET_SUBDIR) ?: "dataset"
        val defaultOut = "metrics_trial_${trialId}.json"
        val outName = intent.getStringExtra(EXTRA_OUT_NAME) ?: defaultOut

        val outFile = File(filesDir, outName)

        val json = JSONObject()
        json.put("trial_id", trialId)
        json.put("split", splitRaw ?: "")

        val split = when (splitRaw) {
            "train", "val", "test" -> splitRaw
            else -> null
        }

        if (split == null) {
            putErrorDefaults(json, "invalid or missing extra '$EXTRA_SPLIT'; use train, val, or test")
            writeJson(outFile, json)
            return
        }

        val splitRoot = File(filesDir, "$datasetSubdir/$split")
        if (!splitRoot.isDirectory) {
            putErrorDefaults(json, "dataset split directory missing: ${splitRoot.absolutePath}")
            writeJson(outFile, json)
            return
        }

        val (samples, sampleError) = collectSamples(filesDir, datasetSubdir, split, splitRoot)
        if (sampleError != null) {
            putErrorDefaults(json, sampleError)
            writeJson(outFile, json)
            return
        }
        if (samples.isEmpty()) {
            putErrorDefaults(json, "no images found under ${splitRoot.absolutePath}")
            writeJson(outFile, json)
            return
        }

        val modelFile = File(filesDir, modelName)
        if (!modelFile.isFile) {
            putErrorDefaults(json, "model file missing: ${modelFile.absolutePath}")
            writeJson(outFile, json)
            return
        }

        val module = try {
            Module.load(modelFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Module.load failed", e)
            putErrorDefaults(json, "Module.load failed: ${e.message}")
            writeJson(outFile, json)
            return
        }

        val latenciesNs = LongArray(samples.size)
        var correct = 0
        var peakUsedMb = 0L

        try {
            samples.forEachIndexed { i, (imageFile, labelIndex) ->
                trackPeakMb()?.let { peakUsedMb = maxOf(peakUsedMb, it) }

                val bitmap = decodeAndResize224(imageFile) ?: run {
                    latenciesNs[i] = 0L
                    return@forEachIndexed
                }

                val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                    bitmap,
                    TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
                    TensorImageUtils.TORCHVISION_NORM_STD_RGB
                )
                safeRecycleAfterTensor(bitmap)

                val start = SystemClock.elapsedRealtimeNanos()
                val outputTensor = module.forward(EValue.from(inputTensor))[0].toTensor()
                latenciesNs[i] = SystemClock.elapsedRealtimeNanos() - start

                val logits = outputTensor.dataAsFloatArray
                val pred = argmax(logits)
                if (pred == labelIndex) correct++

                if (i == 0) {
                    Log.i(TAG, "logits.size=${logits.size} num_samples=${samples.size} split=$split")
                }
                if (i < BENCHMARK_LOG_FIRST || i % BENCHMARK_LOG_EVERY == 0) {
                    Log.i(
                        TAG,
                        "[$i] pred=$pred (${tinyHumanLabel(pred)}) | true=$labelIndex (${tinyHumanLabel(labelIndex)}) | " +
                            "match=${pred == labelIndex} | file=${imageFile.name}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "eval loop failed", e)
            putErrorDefaults(json, "eval failed: ${e.message}")
            writeJson(outFile, json)
            return
        } finally {
            module.destroy()
        }

        val validLatencies = latenciesNs.filter { it > 0L }.sorted()
        val p95Ms = if (validLatencies.isEmpty()) {
            999.0
        } else {
            val idx = ((validLatencies.size - 1) * 0.95).toInt().coerceIn(0, validLatencies.size - 1)
            validLatencies[idx].toDouble() / 1_000_000.0
        }

        val top1 = correct.toDouble() / samples.size.toDouble()
        json.put("top1_acc", top1)
        json.put("latency_p95_ms", p95Ms)
        json.put("num_samples", samples.size)
        json.put("memory_peak_mb", peakUsedMb.toDouble())
        json.put("error", JSONObject.NULL)
        writeJson(outFile, json)
        Log.i(TAG, "Wrote metrics to ${outFile.absolutePath}")
    }

    private fun putErrorDefaults(json: JSONObject, message: String) {
        json.put("error", message)
        json.put("top1_acc", 0.0)
        json.put("latency_p95_ms", 999.0)
        json.put("num_samples", 0)
        json.put("memory_peak_mb", 0.0)
        Log.e(TAG, message)
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    private fun collectSamples(
        filesDir: File,
        datasetSubdir: String,
        split: String,
        splitRoot: File
    ): Pair<List<Pair<File, Int>>, String?> {
        val annFile = File(splitRoot, VAL_ANNOTATIONS_FILE)
        if (split == "val" && annFile.isFile) {
            val wnidToIndex = buildWnidToIndex(filesDir, datasetSubdir, splitRoot)
            if (wnidToIndex.isEmpty()) {
                return emptyList<Pair<File, Int>>() to "Tiny ImageNet val: could not build wnid→class map (add train/ or valid val_annotations.txt)"
            }
            return collectTinyImagenetValSamples(splitRoot, wnidToIndex) to null
        }
        return collectImageFolderSamples(splitRoot) to null
    }

    /**
     * Same order as `torchvision.datasets.ImageFolder` on Tiny ImageNet train: sorted wnid folder names.
     */
    private fun buildWnidToIndex(filesDir: File, datasetSubdir: String, valRoot: File): Map<String, Int> {
        val trainRoot = File(filesDir, "$datasetSubdir/train")
        val orderedWnids: List<String> = if (trainRoot.isDirectory) {
            trainRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } else {
            wnidsFromValAnnotations(valRoot)
        }
        if (orderedWnids.isEmpty()) return emptyMap()
        return orderedWnids.mapIndexed { i, w -> w to i }.toMap()
    }

    private fun wnidsFromValAnnotations(valRoot: File): List<String> {
        val f = File(valRoot, VAL_ANNOTATIONS_FILE)
        if (!f.isFile) return emptyList()
        val seen = HashSet<String>()
        try {
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size >= 2) {
                        val w = parts[1].trim()
                        if (w.isNotEmpty()) seen.add(w)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "read val_annotations.txt failed", e)
            return emptyList()
        }
        return seen.sorted()
    }

    private fun collectTinyImagenetValSamples(
        valRoot: File,
        wnidToIndex: Map<String, Int>
    ): List<Pair<File, Int>> {
        val annFile = File(valRoot, VAL_ANNOTATIONS_FILE)
        val imagesDir = File(valRoot, "images")
        val out = ArrayList<Pair<File, Int>>()
        try {
            annFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split('\t')
                    if (parts.size < 2) return@forEach
                    val fileName = parts[0].trim()
                    val wnid = parts[1].trim()
                    if (fileName.isEmpty() || wnid.isEmpty()) return@forEach
                    val label = wnidToIndex[wnid] ?: run {
                        Log.w(TAG, "unknown wnid in val_annotations: $wnid ($fileName)")
                        return@forEach
                    }
                    val imageFile = listOf(File(imagesDir, fileName), File(valRoot, fileName))
                        .firstOrNull { it.isFile }
                        ?: run {
                            Log.w(TAG, "missing val image: $fileName")
                            return@forEach
                        }
                    if (isImageFile(imageFile)) {
                        out.add(imageFile to label)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "collectTinyImagenetValSamples failed", e)
            return emptyList()
        }
        return out
    }

    /** Class folders sorted lexicographically; label index matches PyTorch ImageFolder `classes`. */
    private fun collectImageFolderSamples(splitRoot: File): List<Pair<File, Int>> {
        val classDirs = splitRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: return emptyList()

        val out = ArrayList<Pair<File, Int>>()
        classDirs.forEachIndexed { classIndex, dir ->
            dir.walkTopDown()
                .filter { it.isFile && isImageFile(it) }
                .forEach { file -> out.add(file to classIndex) }
        }
        return out
    }

    private fun isImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    private fun decodeAndResize224(file: File): Bitmap? {
        return try {
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            Bitmap.createScaledBitmap(decoded, 224, 224, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Do not recycle hardware bitmaps on API 26+ (Bitmap.Config.HARDWARE is API 26 only — guard for minSdk 24).
     */
    @SuppressLint("NewApi")
    private fun safeRecycleAfterTensor(bitmap: Bitmap) {
        val isHardware =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        if (!isHardware) {
            bitmap.recycle()
        }
    }

    /** Debug label text; array order may not match wnid sort — indices are authoritative. */
    private fun tinyHumanLabel(classIndex: Int): String {
        val a = ImageNetClasses.TINY_IMAGENET_200_CLASSES
        return if (classIndex in a.indices) a[classIndex] else "idx:$classIndex"
    }

    private fun argmax(scores: FloatArray): Int {
        if (scores.isEmpty()) return -1
        var best = 0
        var bestVal = scores[0]
        for (i in 1 until scores.size) {
            if (scores[i] > bestVal) {
                bestVal = scores[i]
                best = i
            }
        }
        return best
    }

    /** Rough Java heap high-water during eval (not native ExecTorch allocations). */
    private fun trackPeakMb(): Long? {
        return try {
            val r = Runtime.getRuntime()
            (r.totalMemory() - r.freeMemory()) / (1024 * 1024)
        } catch (_: Exception) {
            null
        }
    }
}
