package com.google.mlkit.vision.demo.kotlin

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build.VERSION_CODES
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskExecutors
import com.google.android.gms.tasks.Tasks
import com.google.android.odml.image.BitmapMlImageBuilder
import com.google.android.odml.image.ByteBufferMlImageBuilder
import com.google.android.odml.image.MediaMlImageBuilder
import com.google.android.odml.image.MlImage
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.demo.BitmapUtils
import com.google.mlkit.vision.demo.CameraImageGraphic
import com.google.mlkit.vision.demo.FrameMetadata
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.InferenceInfoGraphic
import com.google.mlkit.vision.demo.ScopedExecutor
import com.google.mlkit.vision.demo.VisionImageProcessor
import com.google.mlkit.vision.demo.preference.PreferenceUtils
import java.lang.Math.max
import java.lang.Math.min
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask

/**
 * @param <T> The type of the detected feature.
 */
abstract class VisionProcessorBase<T>(context: Context) : VisionImageProcessor {

  companion object {
    const val MANUAL_TESTING_LOG = "LogTagForTest"
    private const val TAG = "VisionProcessorBase"
  }

  private var activityManager: ActivityManager =
    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  private val fpsTimer = Timer()
  private val executor = ScopedExecutor(TaskExecutors.MAIN_THREAD)
  private var isShutdown = false
  private var numRuns = 0
  private var totalFrameMs = 0L
  private var maxFrameMs = 0L
  private var minFrameMs = Long.MAX_VALUE
  private var totalDetectorMs = 0L
  private var maxDetectorMs = 0L
  private var minDetectorMs = Long.MAX_VALUE
  private var frameProcessedInOneSecondInterval = 0
  private var framesPerSecond = 0

  @GuardedBy("this") private var latestImage: ByteBuffer? = null
  @GuardedBy("this") private var latestImageMetaData: FrameMetadata? = null
  @GuardedBy("this") private var processingImage: ByteBuffer? = null
  @GuardedBy("this") private var processingMetaData: FrameMetadata? = null

  init {
    fpsTimer.scheduleAtFixedRate(
      object : TimerTask() {
        override fun run() {
          framesPerSecond = frameProcessedInOneSecondInterval
          frameProcessedInOneSecondInterval = 0
        }
      },
      0,
      1000
    )
  }

  override fun processBitmap(bitmap: Bitmap?, graphicOverlay: GraphicOverlay) {
    val frameStartMs = SystemClock.elapsedRealtime()

    if (isMlImageEnabled(graphicOverlay.context)) {
      val mlImage = BitmapMlImageBuilder(bitmap!!).build()
      requestDetectInImage(
        mlImage,
        graphicOverlay,
        /* originalCameraImage= */ null,
        /* shouldShowFps= */ false,
        frameStartMs
      )
      mlImage.close()
      return
    }

    requestDetectInImage(
      InputImage.fromBitmap(bitmap!!, 0),
      graphicOverlay,
      /* originalCameraImage= */ null,
      /* shouldShowFps= */ false,
      frameStartMs
    )
  }

  @Synchronized
  override fun processByteBuffer(
    data: ByteBuffer?,
    frameMetadata: FrameMetadata?,
    graphicOverlay: GraphicOverlay
  ) {
    latestImage = data
    latestImageMetaData = frameMetadata
    if (processingImage == null && processingMetaData == null) {
      processLatestImage(graphicOverlay)
    }
  }

  @Synchronized
  private fun processLatestImage(graphicOverlay: GraphicOverlay) {
    processingImage = latestImage
    processingMetaData = latestImageMetaData
    latestImage = null
    latestImageMetaData = null
    if (processingImage != null && processingMetaData != null && !isShutdown) {
      processImage(processingImage!!, processingMetaData!!, graphicOverlay)
    }
  }

  private fun processImage(
    data: ByteBuffer,
    frameMetadata: FrameMetadata,
    graphicOverlay: GraphicOverlay
  ) {
    val frameStartMs = SystemClock.elapsedRealtime()
    val bitmap =
      if (PreferenceUtils.isCameraLiveViewportEnabled(graphicOverlay.context)) null
      else BitmapUtils.getBitmap(data, frameMetadata)

    if (isMlImageEnabled(graphicOverlay.context)) {
      val mlImage =
        ByteBufferMlImageBuilder(
            data,
            frameMetadata.width,
            frameMetadata.height,
            MlImage.IMAGE_FORMAT_NV21
          )
          .setRotation(frameMetadata.rotation)
          .build()
      requestDetectInImage(mlImage, graphicOverlay, bitmap, /* shouldShowFps= */ true, frameStartMs)
        .addOnSuccessListener(executor) { processLatestImage(graphicOverlay) }

      mlImage.close()
      return
    }

    requestDetectInImage(
      InputImage.fromByteBuffer(
        data,
        frameMetadata.width,
        frameMetadata.height,
        frameMetadata.rotation,
        InputImage.IMAGE_FORMAT_NV21
      ),
      graphicOverlay,
      bitmap,
      /* shouldShowFps= */ true,
      frameStartMs
    )
      .addOnSuccessListener(executor) { processLatestImage(graphicOverlay) }
  }

  // -----------------Code for processing live preview frame from CameraX API-----------------------
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  @ExperimentalGetImage
  override fun processImageProxy(image: ImageProxy, graphicOverlay: GraphicOverlay) {
    val frameStartMs = SystemClock.elapsedRealtime()
    if (isShutdown) {
      return
    }
    var bitmap: Bitmap? = null
    if (!PreferenceUtils.isCameraLiveViewportEnabled(graphicOverlay.context)) {
      bitmap = BitmapUtils.getBitmap(image)
    }

    if (isMlImageEnabled(graphicOverlay.context)) {
      val mlImage =
        MediaMlImageBuilder(image.image!!).setRotation(image.imageInfo.rotationDegrees).build()
      requestDetectInImage(
        mlImage,
        graphicOverlay,
        /* originalCameraImage= */ bitmap,
        /* shouldShowFps= */ true,
        frameStartMs
      )
        .addOnCompleteListener { image.close() }

      return
    }

    requestDetectInImage(
      InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees),
      graphicOverlay,
      /* originalCameraImage= */ bitmap,
      /* shouldShowFps= */ true,
      frameStartMs
    )
      .addOnCompleteListener { image.close() }
  }

  // -----------------Common processing logic-------------------------------------------------------
  private fun requestDetectInImage(
    image: InputImage,
    graphicOverlay: GraphicOverlay,
    originalCameraImage: Bitmap?,
    shouldShowFps: Boolean,
    frameStartMs: Long
  ): Task<T> {
    return setUpListener(
      detectInImage(image),
      graphicOverlay,
      originalCameraImage,
      shouldShowFps,
      frameStartMs
    )
  }

  private fun requestDetectInImage(
    image: MlImage,
    graphicOverlay: GraphicOverlay,
    originalCameraImage: Bitmap?,
    shouldShowFps: Boolean,
    frameStartMs: Long
  ): Task<T> {
    return setUpListener(
      detectInImage(image),
      graphicOverlay,
      originalCameraImage,
      shouldShowFps,
      frameStartMs
    )
  }

  private fun setUpListener(
    task: Task<T>,
    graphicOverlay: GraphicOverlay,
    originalCameraImage: Bitmap?,
    shouldShowFps: Boolean,
    frameStartMs: Long
  ): Task<T> {
    val detectorStartMs = SystemClock.elapsedRealtime()
    return task
      .addOnSuccessListener(
        executor,
        OnSuccessListener { results: T ->
          val endMs = SystemClock.elapsedRealtime()
          val currentFrameLatencyMs = endMs - frameStartMs
          val currentDetectorLatencyMs = endMs - detectorStartMs
          if (numRuns >= 500) {
            resetLatencyStats()
          }
          numRuns++
          frameProcessedInOneSecondInterval++
          totalFrameMs += currentFrameLatencyMs
          maxFrameMs = max(currentFrameLatencyMs, maxFrameMs)
          minFrameMs = min(currentFrameLatencyMs, minFrameMs)
          totalDetectorMs += currentDetectorLatencyMs
          maxDetectorMs = max(currentDetectorLatencyMs, maxDetectorMs)
          minDetectorMs = min(currentDetectorLatencyMs, minDetectorMs)

          // Only log inference info once per second. When frameProcessedInOneSecondInterval is
          // equal to 1, it means this is the first frame processed during the current second.
          if (frameProcessedInOneSecondInterval == 1) {
            Log.d(TAG, "Num of Runs: $numRuns")
            Log.d(
              TAG,
              "Frame latency: max=" +
                maxFrameMs +
                ", min=" +
                minFrameMs +
                ", avg=" +
                totalFrameMs / numRuns
            )
            Log.d(
              TAG,
              "Detector latency: max=" +
                maxDetectorMs +
                ", min=" +
                minDetectorMs +
                ", avg=" +
                totalDetectorMs / numRuns
            )
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)
            val availableMegs: Long = mi.availMem / 0x100000L
            Log.d(TAG, "Memory available in system: $availableMegs MB")
          }
          graphicOverlay.clear()
          if (originalCameraImage != null) {
            graphicOverlay.add(CameraImageGraphic(graphicOverlay, originalCameraImage))
          }
          this@VisionProcessorBase.onSuccess(results, graphicOverlay)
          if (!PreferenceUtils.shouldHideDetectionInfo(graphicOverlay.context)) {
            graphicOverlay.add(
              InferenceInfoGraphic(
                graphicOverlay,
                currentFrameLatencyMs,
                currentDetectorLatencyMs,
                if (shouldShowFps) framesPerSecond else null
              )
            )
          }
          graphicOverlay.postInvalidate()
        }
      )
      .addOnFailureListener(
        executor,
        OnFailureListener { e: Exception ->
          graphicOverlay.clear()
          graphicOverlay.postInvalidate()
          val error = "Failed to process. Error: " + e.localizedMessage
          Toast.makeText(
              graphicOverlay.context,
              """
          $error
          Cause: ${e.cause}
          """.trimIndent(),
              Toast.LENGTH_SHORT
            )
            .show()
          Log.d(TAG, error)
          e.printStackTrace()
          this@VisionProcessorBase.onFailure(e)
        }
      )
  }

  override fun stop() {
    executor.shutdown()
    isShutdown = true
    resetLatencyStats()
    fpsTimer.cancel()
  }

  private fun resetLatencyStats() {
    numRuns = 0
    totalFrameMs = 0
    maxFrameMs = 0
    minFrameMs = Long.MAX_VALUE
    totalDetectorMs = 0
    maxDetectorMs = 0
    minDetectorMs = Long.MAX_VALUE
  }

  protected abstract fun detectInImage(image: InputImage): Task<T>

  protected open fun detectInImage(image: MlImage): Task<T> {
    return Tasks.forException(
      MlKitException(
        "MlImage is currently not demonstrated for this feature",
        MlKitException.INVALID_ARGUMENT
      )
    )
  }

  protected abstract fun onSuccess(results: T, graphicOverlay: GraphicOverlay)

  protected abstract fun onFailure(e: Exception)

  protected open fun isMlImageEnabled(context: Context?): Boolean {
    return false
  }
}
