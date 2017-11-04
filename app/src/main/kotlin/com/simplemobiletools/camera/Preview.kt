package com.simplemobiletools.camera

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.Camera
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.coremedia.iso.IsoFile
import com.coremedia.iso.boxes.TrackBox
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Mp4TrackImpl
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.simplemobiletools.camera.activities.MainActivity
import com.simplemobiletools.camera.dialogs.ChangeResolutionDialog
import com.simplemobiletools.camera.extensions.*
import com.simplemobiletools.commons.extensions.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.*

class Preview : ViewGroup, SurfaceHolder.Callback, MediaScannerConnection.OnScanCompletedListener {
    var mCamera: Camera? = null
    private val TAG = Preview::class.java.simpleName
    private val FOCUS_AREA_SIZE = 100
    private val PHOTO_PREVIEW_LENGTH = 500L
    private val REFOCUS_PERIOD = 3000L

    lateinit var mSurfaceHolder: SurfaceHolder
    lateinit var mSurfaceView: SurfaceView
    lateinit var mActivity: MainActivity
    lateinit var mCallback: PreviewListener
    lateinit var mScreenSize: Point
    lateinit var config: Config
    private var mSupportedPreviewSizes: List<Camera.Size>? = null
    private var mPreviewSize: Camera.Size? = null
    private var mParameters: Camera.Parameters? = null
    private var mRecorder: MediaRecorder? = null
    private var mScaleGestureDetector: ScaleGestureDetector? = null
    private var mZoomRatios: List<Int>? = null

    private var mCurrVideoPath = ""
    private var mCanTakePicture = false
    private var mIsRecording = false
    private var mIsVideoMode = false
    private var mIsSurfaceCreated = false
    private var mSwitchToVideoAsap = false
    private var mSetupPreviewAfterMeasure = false
    private var mIsSixteenToNine = false
    private var mWasZooming = false
    private var mIsPreviewShown = false
    private var mLastClickX = 0
    private var mLastClickY = 0
    private var mCurrCameraId = 0
    private var mMaxZoom = 0
    private var mRotationAtCapture = 0
    private var mIsFocusingBeforeCapture = false
    private var autoFocusHandler = Handler()

    var isWaitingForTakePictureCallback = false
    var mTargetUri: Uri? = null
    var isImageCaptureIntent = false

    constructor(context: Context) : super(context)

    constructor(activity: MainActivity, surfaceView: SurfaceView, previewListener: PreviewListener) : super(activity) {
        mActivity = activity
        mCallback = previewListener
        mSurfaceView = surfaceView
        mSurfaceHolder = mSurfaceView.holder
        mSurfaceHolder.addCallback(this)
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
        mCanTakePicture = false
        mIsVideoMode = false
        mIsSurfaceCreated = false
        mSetupPreviewAfterMeasure = false
        mCurrVideoPath = ""
        config = activity.config
        mScreenSize = getScreenSize()
        initGestureDetector()

        mSurfaceView.setOnTouchListener { view, event ->
            mLastClickX = event.x.toInt()
            mLastClickY = event.y.toInt()

            if (mMaxZoom > 0)
                mScaleGestureDetector!!.onTouchEvent(event)
            false
        }

        mSurfaceView.setOnClickListener {
            if (mIsPreviewShown) {
                resumePreview()
            } else {
                if (!mWasZooming && !mIsPreviewShown)
                    focusArea(false)

                mWasZooming = false
                mSurfaceView.isSoundEffectsEnabled = true
            }
        }
    }

    fun trySwitchToVideo() {
        if (mIsSurfaceCreated) {
            initRecorder()
        } else {
            mSwitchToVideoAsap = true
        }
    }

    fun setCamera(cameraId: Int): Boolean {
        mCurrCameraId = cameraId
        val newCamera: Camera
        try {
            newCamera = Camera.open(cameraId)
            mCallback.setIsCameraAvailable(true)
        } catch (e: Exception) {
            mActivity.toast(R.string.camera_open_error)
            Log.e(TAG, "setCamera open " + e.message)
            mCallback.setIsCameraAvailable(false)
            return false
        }

        if (mCamera === newCamera) {
            return false
        }

        releaseCamera()
        mCamera = newCamera
        if (initCamera() && mIsVideoMode) {
            initRecorder()
        }

        return true
    }

    private fun initCamera(): Boolean {
        if (mCamera == null)
            return false

        mParameters = mCamera!!.parameters
        mMaxZoom = mParameters!!.maxZoom
        mZoomRatios = mParameters!!.zoomRatios
        mSupportedPreviewSizes = mParameters!!.supportedPreviewSizes.sortedByDescending { it.width * it.height }
        refreshPreview()

        val focusModes = mParameters!!.supportedFocusModes
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
            mParameters!!.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE

        mCamera!!.setDisplayOrientation(mActivity.getPreviewRotation(mCurrCameraId))
        mCamera!!.parameters = mParameters

        if (mCanTakePicture) {
            try {
                mCamera!!.setPreviewDisplay(mSurfaceHolder)
            } catch (e: IOException) {
                Log.e(TAG, "setCamera setPreviewDisplay " + e.message)
                return false
            }
        }

//        mCallback.setFlashAvailable(hasFlash(mCamera))
        return true
    }

    private fun refreshPreview() {
        mIsSixteenToNine = getSelectedResolution().isSixteenToNine()
        mSetupPreviewAfterMeasure = true
        requestLayout()
        invalidate()
        rescheduleAutofocus()
    }

    private fun getSelectedResolution(): Camera.Size {
        if (mParameters == null)
            mParameters = mCamera!!.parameters

        var index = getResolutionIndex()
        val resolutions = if (mIsVideoMode) {
            mParameters!!.supportedVideoSizes ?: mParameters!!.supportedPreviewSizes
        } else {
            mParameters!!.supportedPictureSizes
        }.sortedByDescending { it.width * it.height }

        if (index == -1) {
            index = getDefaultFullscreenResolution(resolutions) ?: 0
        }

        return resolutions[index]
    }

    private fun getResolutionIndex(): Int {
        val isBackCamera = config.lastUsedCamera == Camera.CameraInfo.CAMERA_FACING_BACK
        return if (mIsVideoMode) {
            if (isBackCamera) config.backVideoResIndex else config.frontVideoResIndex
        } else {
            if (isBackCamera) config.backPhotoResIndex else config.frontPhotoResIndex
        }
    }

    private fun getDefaultFullscreenResolution(resolutions: List<Camera.Size>): Int? {
        val screenAspectRatio = mActivity.realScreenSize.y / mActivity.realScreenSize.x.toFloat()
        resolutions.forEachIndexed { index, size ->
            val diff = screenAspectRatio - (size.width / size.height.toFloat())
            if (Math.abs(diff) < RATIO_TOLERANCE) {
                config.backPhotoResIndex = index
                return index
            }
        }
        return null
    }

    private fun initGestureDetector() {
        mScaleGestureDetector = ScaleGestureDetector(mActivity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomFactor = mParameters!!.zoom
                var zoomRatio = mZoomRatios!![zoomFactor] / 100f
                zoomRatio *= detector.scaleFactor

                var newZoomFactor = zoomFactor
                if (zoomRatio <= 1f) {
                    newZoomFactor = 0
                } else if (zoomRatio >= mZoomRatios!![mMaxZoom] / 100f) {
                    newZoomFactor = mMaxZoom
                } else {
                    if (detector.scaleFactor > 1f) {
                        for (i in zoomFactor..mZoomRatios!!.size - 1) {
                            if (mZoomRatios!![i] / 100.0f >= zoomRatio) {
                                newZoomFactor = i
                                break
                            }
                        }
                    } else {
                        for (i in zoomFactor downTo 0) {
                            if (mZoomRatios!![i] / 100.0f <= zoomRatio) {
                                newZoomFactor = i
                                break
                            }
                        }
                    }
                }

                newZoomFactor = Math.max(newZoomFactor, 0)
                newZoomFactor = Math.min(mMaxZoom, newZoomFactor)

                mParameters!!.zoom = newZoomFactor
                mCamera?.parameters = mParameters
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                super.onScaleEnd(detector)
                mWasZooming = true
                mSurfaceView.isSoundEffectsEnabled = false
                mParameters!!.focusAreas = null
            }
        })
    }

    fun tryTakePicture() {
        if (config.focusBeforeCapture) {
            focusArea(true)
        } else {
            takePicture()
        }
    }

    private fun takePicture() {
        if (mCanTakePicture) {
            val selectedResolution = getSelectedResolution()
            mParameters!!.setPictureSize(selectedResolution.width, selectedResolution.height)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                mCamera!!.enableShutterSound(false)
            }

            mRotationAtCapture = MainActivity.mLastHandledOrientation
            mCamera!!.parameters = mParameters
            isWaitingForTakePictureCallback = true
            mIsPreviewShown = true
            mCamera!!.takePicture(null, null, takePictureCallback)

            if (config.isSoundEnabled) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val volume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                if (volume != 0) {
                    val mp = MediaPlayer.create(context, Uri.parse("file:///system/media/audio/ui/camera_click.ogg"))
                    mp?.start()
                }
            }
        }
        mCanTakePicture = false
        mIsFocusingBeforeCapture = false
    }

    private val takePictureCallback = Camera.PictureCallback { data, cam ->
        isWaitingForTakePictureCallback = false
        if (!isImageCaptureIntent) {
            handlePreview()
        }

        if (isImageCaptureIntent) {
            if (mTargetUri != null) {
                storePhoto(data)
            } else {
                mActivity.finishActivity()
            }
        } else {
            storePhoto(data)
        }
    }

    private fun storePhoto(data: ByteArray) {
        PhotoProcessor(mActivity, mTargetUri, mCurrCameraId, mRotationAtCapture).execute(data)
    }

    private fun handlePreview() {
        if (config.isShowPreviewEnabled) {
            if (!config.wasPhotoPreviewHintShown) {
                mActivity.toast(R.string.click_to_resume_preview)
                config.wasPhotoPreviewHintShown = true
            }
        } else {
            Handler().postDelayed({
                mIsPreviewShown = false
                resumePreview()
            }, PHOTO_PREVIEW_LENGTH)
        }
    }

    private fun resumePreview() {
        mIsPreviewShown = false
        mActivity.toggleBottomButtons(false)
        try {
            mCamera?.startPreview()
        } catch (e: Exception) {
        }
        mCanTakePicture = true
        focusArea(false, false)
    }

    private fun focusArea(takePictureAfter: Boolean, showFocusRect: Boolean = true) {
        if (mCamera == null || (mIsFocusingBeforeCapture && !takePictureAfter))
            return

        if (takePictureAfter)
            mIsFocusingBeforeCapture = true

        mCamera!!.cancelAutoFocus()
        if (mParameters!!.maxNumFocusAreas > 0) {
            if (mLastClickX == 0 && mLastClickY == 0) {
                mLastClickX = width / 2
                mLastClickY = height / 2
            }
            val focusRect = calculateFocusArea(mLastClickX.toFloat(), mLastClickY.toFloat())
            val focusAreas = ArrayList<Camera.Area>(1)
            focusAreas.add(Camera.Area(focusRect, 1000))
            mParameters!!.focusAreas = focusAreas

            if (showFocusRect)
                mCallback.drawFocusRect(mLastClickX, mLastClickY)
        }

        mCamera!!.parameters = mParameters
        try {
            mCamera!!.autoFocus { success, camera ->
                if (camera == null || mCamera == null) {
                    return@autoFocus
                }
                camera.cancelAutoFocus()
                val focusModes = mParameters!!.supportedFocusModes
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                    mParameters!!.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE

                camera.parameters = mParameters

                if (takePictureAfter) {
                    takePicture()
                } else {
                    rescheduleAutofocus()
                }
            }
        } catch (ignored: RuntimeException) {

        }
    }

    private fun calculateFocusArea(x: Float, y: Float): Rect {
        var left = x / mSurfaceView.width * 2000 - 1000
        var top = y / mSurfaceView.height * 2000 - 1000

        val tmp = left
        left = top
        top = -tmp

        val rectLeft = Math.max(left.toInt() - FOCUS_AREA_SIZE / 2, -1000)
        val rectTop = Math.max(top.toInt() - FOCUS_AREA_SIZE / 2, -1000)
        val rectRight = Math.min(left.toInt() + FOCUS_AREA_SIZE / 2, 1000)
        val rectBottom = Math.min(top.toInt() + FOCUS_AREA_SIZE / 2, 1000)
        return Rect(rectLeft, rectTop, rectRight, rectBottom)
    }

    private fun rescheduleAutofocus() {
        autoFocusHandler.removeCallbacksAndMessages(null)
        autoFocusHandler.postDelayed({
            if (!mIsVideoMode || !mIsRecording) {
                focusArea(false, false)
            }
        }, REFOCUS_PERIOD)
    }

    fun showChangeResolutionDialog() {
        if (mCamera != null) {
            val oldResolution = getSelectedResolution()
            ChangeResolutionDialog(mActivity, config, mCamera!!) {
                if (oldResolution != getSelectedResolution()) {
                    refreshPreview()
                }
            }
        }
    }

    fun releaseCamera() {
        stopRecording()
        mCamera?.stopPreview()
        mCamera?.release()
        mCamera = null
        cleanupRecorder()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mIsSurfaceCreated = true
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)

            if (mSwitchToVideoAsap)
                initRecorder()
        } catch (e: IOException) {
            Log.e(TAG, "surfaceCreated IOException " + e.message)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mIsSurfaceCreated = true

        if (mIsVideoMode) {
            initRecorder()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mIsSurfaceCreated = false
        mCamera?.stopPreview()
        cleanupRecorder()
    }

    private fun setupPreview() {
        mCanTakePicture = true
        if (mCamera != null && mPreviewSize != null) {
            if (mParameters == null)
                mParameters = mCamera!!.parameters

            mParameters!!.setPreviewSize(mPreviewSize!!.width, mPreviewSize!!.height)
            mCamera!!.parameters = mParameters
            try {
                mCamera!!.startPreview()
            } catch (ignored: RuntimeException) {
            }
        }
    }

    private fun cleanupRecorder() {
        if (mRecorder != null) {
            if (mIsRecording) {
                stopRecording()
            }

            mRecorder!!.release()
            mRecorder = null
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

    }

    private fun getOptimalPreviewSize(sizes: List<Camera.Size>, width: Int, height: Int): Camera.Size {
        var result: Camera.Size? = null
        for (size in sizes) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size
                } else {
                    val resultArea = result.width * result.height
                    val newArea = size.width * size.height

                    if (newArea > resultArea) {
                        result = size
                    }
                }
            }
        }

        return result!!
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(mScreenSize.x, mScreenSize.y)

        if (mSupportedPreviewSizes != null) {
            // for simplicity lets assume that most displays are 16:9 and the remaining ones are 4:3
            // always set 16:9 for videos as many devices support 4:3 only in low quality
            if (mIsSixteenToNine || mIsVideoMode) {
                mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes!!, mScreenSize.y, mScreenSize.x)
            } else {
                val newRatioHeight = (mScreenSize.x * (4.toDouble() / 3)).toInt()
                setMeasuredDimension(mScreenSize.x, newRatioHeight)
                mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes!!, newRatioHeight, mScreenSize.x)
            }
            val lp = mSurfaceView.layoutParams

            // make sure to occupy whole width in every case
            if (mScreenSize.x > mPreviewSize!!.height) {
                val ratio = mScreenSize.x.toFloat() / mPreviewSize!!.height
                lp.width = (mPreviewSize!!.height * ratio).toInt()
                if (mIsSixteenToNine || mIsVideoMode) {
                    lp.height = mScreenSize.y
                } else {
                    lp.height = (mPreviewSize!!.width * ratio).toInt()
                }
            } else {
                lp.width = mPreviewSize!!.height
                lp.height = mPreviewSize!!.width
            }

            if (mSetupPreviewAfterMeasure) {
                if (mCamera != null) {
                    mSetupPreviewAfterMeasure = false
                    mCamera!!.stopPreview()
                    setupPreview()
                }
            }
        }
    }

    fun enableFlash() {
        mParameters!!.flashMode = Camera.Parameters.FLASH_MODE_TORCH
        mCamera!!.parameters = mParameters
    }

    fun disableFlash() {
        mParameters!!.flashMode = Camera.Parameters.FLASH_MODE_OFF
        mCamera!!.parameters = mParameters
    }

    fun autoFlash() {
        mParameters!!.flashMode = Camera.Parameters.FLASH_MODE_OFF
        mCamera!!.parameters = mParameters

        Handler().postDelayed({
            mActivity.runOnUiThread {
                mParameters?.flashMode = Camera.Parameters.FLASH_MODE_AUTO
                mCamera?.parameters = mParameters
            }
        }, 1000)
    }

    fun initPhotoMode() {
        stopRecording()
        cleanupRecorder()
        mIsRecording = false
        mIsVideoMode = false
        refreshPreview()
    }

    // VIDEO RECORDING
    fun initRecorder(): Boolean {
        if (mCamera == null || mRecorder != null || !mIsSurfaceCreated)
            return false

        refreshPreview()
        mSwitchToVideoAsap = false

        mIsRecording = false
        mIsVideoMode = true
        mRecorder = MediaRecorder().apply {
            setCamera(mCamera)
            setVideoSource(MediaRecorder.VideoSource.DEFAULT)
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
        }

        mCurrVideoPath = mActivity.getOutputMediaFile(false)
        if (mCurrVideoPath.isEmpty()) {
            mActivity.toast(R.string.video_creating_error)
            return false
        }

        val resolution = getSelectedResolution()
        CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH).apply {
            videoFrameWidth = resolution.width
            videoFrameHeight = resolution.height
            mRecorder!!.setProfile(this)
        }

        checkPermissions()
        mRecorder!!.setPreviewDisplay(mSurfaceHolder.surface)

        val rotation = getVideoRotation()
        mRecorder!!.setOrientationHint(rotation)

        try {
            mRecorder!!.prepare()
        } catch (e: Exception) {
            setupFailed(e)
            return false
        }

        return true
    }

    private fun checkPermissions(): Boolean {
        if (mActivity.needsStupidWritePermissions(mCurrVideoPath)) {
            if (config.treeUri.isEmpty()) {
                mActivity.toast(R.string.save_error_internal_storage)
                config.savePhotosFolder = Environment.getExternalStorageDirectory().toString()
                releaseCamera()
                return false
            }

            try {
                var document = mActivity.getFileDocument(mCurrVideoPath)
                if (document == null) {
                    mActivity.toast(R.string.unknown_error_occurred)
                    return false
                }

                document = document.createFile("video/mp4", mCurrVideoPath.substring(mCurrVideoPath.lastIndexOf('/') + 1))
                val fileDescriptor = context.contentResolver.openFileDescriptor(document.uri, "rw")
                mRecorder!!.setOutputFile(fileDescriptor!!.fileDescriptor)
            } catch (e: Exception) {
                setupFailed(e)
            }
        } else {
            mRecorder!!.setOutputFile(mCurrVideoPath)
        }
        return true
    }

    private fun setupFailed(e: Exception) {
        mActivity.showErrorToast(e)
        Log.e(TAG, "initRecorder " + e.message)
        releaseCamera()
    }

    fun toggleRecording(): Boolean {
        if (mIsRecording) {
            stopRecording()
            initRecorder()
        } else {
            startRecording()
        }
        return mIsRecording
    }

    private fun getVideoRotation(): Int {
        val deviceRot = MainActivity.mLastHandledOrientation.compensateDeviceRotation(mCurrCameraId)
        val previewRot = mActivity.getPreviewRotation(mCurrCameraId)
        return (deviceRot + previewRot) % 360
    }

    fun deviceOrientationChanged() {
        if (mIsVideoMode && !mIsRecording) {
            mRecorder = null
            initRecorder()
        }
    }

    private fun startRecording() {
        try {
            mCamera!!.unlock()
            toggleShutterSound(true)
            mRecorder!!.start()
            toggleShutterSound(false)
            mIsRecording = true
        } catch (e: Exception) {
            mActivity.showErrorToast(e)
            Log.e(TAG, "toggleRecording " + e.message)
            releaseCamera()
        }
    }

    private fun stopRecording() {
        if (mRecorder != null && mIsRecording) {
            try {
                toggleShutterSound(true)
                mRecorder!!.stop()
                mActivity.scanPath(mCurrVideoPath) {}
            } catch (e: RuntimeException) {
                toggleShutterSound(false)
                File(mCurrVideoPath).delete()
                mActivity.showErrorToast(e)
                Log.e(TAG, "stopRecording " + e.message)
                mRecorder = null
                mIsRecording = false
                releaseCamera()
            }
        }

        mRecorder = null
        mIsRecording = false

        val file = File(mCurrVideoPath)
        if (file.exists() && file.length() == 0L) {
            file.delete()
        }else {
            try {
                //밀렸을 경우 조정해주기
                val channel = FileDataSourceImpl(file)
                val isoFile = IsoFile(channel)

                val trackBoxes = isoFile.getMovieBox().getBoxes(TrackBox::class.java)
                var sampleError = false
                for (trackBox in trackBoxes) {
                    val firstEntry = trackBox.getMediaBox().getMediaInformationBox().getSampleTableBox().getTimeToSampleBox().getEntries().get(0)

                    // Detect if first sample is a problem and fix it in isoFile
                    // This is a hack. The audio deltas are 1024 for my files, and video deltas about 3000
                    // 10000 seems sufficient since for 30 fps the normal delta is about 3000
                    if (firstEntry.getDelta() > 6000) {
                        sampleError = true
                        firstEntry.setDelta(3000)
                    }
                }
                var videoDelta = trackBoxes.get(0).mediaBox.mediaInformationBox.sampleTableBox.timeToSampleBox.entries.get(0).delta
                var audioDelta = trackBoxes.get(1).mediaBox.mediaInformationBox.sampleTableBox.timeToSampleBox.entries.get(0).delta

                //내꺼 비디오 2997 사운드 1314
                //오빠꺼 비디오 4562 사운드 1024
                if (sampleError) {
                    val movie = Movie()
                    for (trackBox in trackBoxes) {
                        movie.addTrack(Mp4TrackImpl(channel.toString() + "[" + trackBox.getTrackHeaderBox().getTrackId() + "]", trackBox))
                    }
                    movie.matrix = isoFile.getMovieBox().getMovieHeaderBox().getMatrix()
                    val out = DefaultMp4Builder().build(movie)

                    //delete file first!
                    val fc = RandomAccessFile(file.getName(), "rw").getChannel()
                    out.writeContainer(fc)
                    fc.close()
                    Log.d(GifHeaderParser.TAG, "Finished correcting raw video")
                }
            }catch (e : Exception){}
        }
    }

    private fun toggleShutterSound(mute: Boolean?) {
        if (!config.isSoundEnabled) {
            (mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamMute(AudioManager.STREAM_SYSTEM, mute!!)
        }
    }

    override fun onScanCompleted(path: String, uri: Uri) {
        mCallback.videoSaved(uri)
        toggleShutterSound(false)
    }

//    private fun hasFlash(camera: Camera?): Boolean {
//        if (camera == null) {
//            return false
//        }
//
//        if (camera.parameters.flashMode == null) {
//            return false
//        }
//
//        val supportedFlashModes = camera.parameters.supportedFlashModes
//        if (supportedFlashModes == null || supportedFlashModes.isEmpty() ||
//                supportedFlashModes.size == 1 && supportedFlashModes[0] == Camera.Parameters.FLASH_MODE_OFF) {
//            return false
//        }
//
//        return true
//    }

    private fun getScreenSize(): Point {
        val display = mActivity.windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        size.y += mActivity.resources.getNavBarHeight()
        return size
    }

    interface PreviewListener {
//        fun setFlashAvailable(available: Boolean)

        fun setIsCameraAvailable(available: Boolean)

        fun videoSaved(uri: Uri)

        fun drawFocusRect(x: Int, y: Int)
    }
}
