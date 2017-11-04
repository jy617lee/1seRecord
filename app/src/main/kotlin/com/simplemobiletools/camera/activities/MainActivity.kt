package com.simplemobiletools.camera.activities

//import com.simplemobiletools.camera.R.id.toggle_flash
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.hardware.Camera
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.view.*
import android.widget.RelativeLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.AppendTrack
import com.simplemobiletools.camera.*
import com.simplemobiletools.camera.Preview.PreviewListener
import com.simplemobiletools.camera.extensions.config
import com.simplemobiletools.camera.extensions.navBarHeight
import com.simplemobiletools.camera.views.FocusRectView
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.models.Release
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

//todo : base activity가 뭐징
class MainActivity : SimpleActivity(), PreviewListener, PhotoProcessor.MediaSavedListener {
    //inner class = conpanion object
    companion object {
        private val CAMERA_STORAGE_PERMISSION = 1
        private val RECORD_AUDIO_PERMISSION = 2
        private val FADE_DELAY = 5000

        lateinit var mFocusRectView: FocusRectView
        lateinit var mTimerHandler: Handler
        lateinit var mFadeHandler: Handler
        lateinit var mRes: Resources

//        var a : Int? 정의부 말미에 ?를 붙이게 되면 nullable
        private var mPreview: Preview? = null
        private var mPreviewUri: Uri? = null
        private var mFlashlightState = FLASH_OFF
        private var mIsInPhotoMode = false
        private var mIsAskingPermissions = false
        private var mIsCameraAvailable = false
        private var mIsVideoCaptureIntent = false
        private var mIsHardwareShutterHandled = false
        private var mCurrVideoRecTimer = 0
        private var mCurrCameraId = 0
        var mLastHandledOrientation = 0
    }

    lateinit var mOrientationEventListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        initVariables()
        tryInitCamera()
        supportActionBar?.hide()
        storeStoragePaths()
        checkWhatsNewDialog()
        setupOrientationEventListener()
    }

    private fun initVariables() {
        mRes = resources
        mIsInPhotoMode = false
        mIsAskingPermissions = false
        mIsCameraAvailable = false
        mIsVideoCaptureIntent = false
        mIsHardwareShutterHandled = false
        mCurrVideoRecTimer = 0
        mCurrCameraId = 0
        mLastHandledOrientation = 0
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_CAMERA && !mIsHardwareShutterHandled) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else if (config.volumeButtonsAsShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            shutterPressed()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            mIsHardwareShutterHandled = false
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hideToggleModeAbout() {
//        toggle_photo_video.visibility = View.GONE
        settings.visibility = View.GONE
    }

    private fun tryInitCamera() {
        if (hasCameraAndStoragePermission()) {
            initializeCamera()
            handleIntent()
        } else {
            val permissions = ArrayList<String>(3)
            if (!hasCameraPermission()) {
                permissions.add(Manifest.permission.CAMERA)
            }
            if (!hasWriteStoragePermission()) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if(!hasRecordAudioPermission()){
                permissions.add(Manifest.permission.RECORD_AUDIO)
            }
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), CAMERA_STORAGE_PERMISSION)
        }
    }

    private fun handleIntent() {
        if (isImageCaptureIntent()) {
            hideToggleModeAbout()
            val output = intent.extras?.get(MediaStore.EXTRA_OUTPUT)
            if (output != null && output is Uri) {
                mPreview?.mTargetUri = output
            }
        } else if (intent?.action == MediaStore.ACTION_VIDEO_CAPTURE) {
            mIsVideoCaptureIntent = true
            hideToggleModeAbout()
            shutter.setImageDrawable(mRes.getDrawable(R.drawable.ic_video_rec))
        }
        mPreview?.isImageCaptureIntent = isImageCaptureIntent()
    }

    private fun isImageCaptureIntent() = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE || intent?.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE

    private fun initializeCamera() {
        setContentView(R.layout.activity_main)
        initButtons()

        (btn_holder.layoutParams as RelativeLayout.LayoutParams).setMargins(0, 0, 0, (navBarHeight + mRes.getDimension(R.dimen.activity_margin)).toInt())

        mCurrCameraId = config.lastUsedCamera
        mPreview = Preview(this, camera_view, this)
        mPreview!!.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view_holder.addView(mPreview)
        toggle_camera.setImageResource(if (mCurrCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) R.drawable.ic_camera_front else R.drawable.ic_camera_rear)

        mFocusRectView = FocusRectView(applicationContext)
        view_holder.addView(mFocusRectView)

        mIsInPhotoMode = false
        mIsVideoCaptureIntent = true
        mTimerHandler = Handler()
        mFadeHandler = Handler()
        mFlashlightState = if (config.turnFlashOffAtStartup) FLASH_OFF else config.flashlightState
        setupPreviewImage(true)
    }

    private fun initButtons() {
        toggle_camera.setOnClickListener { toggleCamera() }
        last_photo_video_preview.setOnClickListener { showLastMediaPreview() }
//        toggle_flash.setOnClickListener { toggleFlash() }
        shutter.setOnClickListener { shutterPressed() }
        settings.setOnClickListener { launchSettings() }
//        toggle_photo_video.setOnClickListener { handleTogglePhotoVideo() }
        change_resolution.setOnClickListener { mPreview?.showChangeResolutionDialog() }
        make_diary.setOnClickListener{ makeDiary()}
    }

    var cntFiles : Int = 0
    fun makeDiary(){
        //그 폴더의 uri모두 가져오기
        var dirRootPath = File(config.savePhotosFolder)
        cntFiles = dirRootPath.listFiles().size
        var videoUris = ArrayList<String>(cntFiles)
        for(video in dirRootPath.listFiles()){
            if(video.length() != 0L){
                videoUris.add(video.absolutePath)
            }
        }

        //리스트에 비디오 저장하기
        var videoTrack = LinkedList<Track>()
        var audioTrack = LinkedList<Track>()
        for(videoUri in videoUris){
            var movie = MovieCreator.build(videoUri)
            //오디오가 밀릴 경우

            for(track in movie.tracks){
                if(track.handler.equals("soun")){
                    audioTrack.add(track)
                }else if(track.handler.equals("vide")) {
                    videoTrack.add(track)
                }
            }
        }

        //하나로 만들기
        var result = Movie()
        if(!audioTrack.isEmpty()){
            result.addTrack(AppendTrack(*audioTrack.toTypedArray()))
        }

        if(!videoTrack.isEmpty()){
            result.addTrack(AppendTrack(*videoTrack.toTypedArray()))
        }

        //저장하기
        var out = DefaultMp4Builder().build(result)

//        var file = File(getOutputMediaFile(false) + File.separator + "videoDiary.mp4")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fc = RandomAccessFile(config.savePhotosFolder + File.separator + "videoDiary" + timestamp + ".mp4", "rw").channel
        out.writeContainer(fc)
        fc.close()
    }
    private fun hasCameraAndStoragePermission() = hasCameraPermission() && hasWriteStoragePermission()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        mIsAskingPermissions = false

        if (requestCode == CAMERA_STORAGE_PERMISSION) {
            if (hasCameraAndStoragePermission()) {
                initializeCamera()
                handleIntent()
            } else {
                toast(R.string.no_permissions)
                finish()
            }
        } else if (requestCode == RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                togglePhotoVideo()
            } else {
                toast(R.string.no_audio_permissions)
                if (mIsVideoCaptureIntent)
                    finish()
            }
        }
    }

    private fun toggleCamera() {
        if (!checkCameraAvailable()) {
            return
        }

        mCurrCameraId = if (mCurrCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        } else {
            Camera.CameraInfo.CAMERA_FACING_BACK
        }

        config.lastUsedCamera = mCurrCameraId
        var newIconId = R.drawable.ic_camera_front
        mPreview?.releaseCamera()
        if (mPreview?.setCamera(mCurrCameraId) == true) {
            if (mCurrCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                newIconId = R.drawable.ic_camera_rear
            }
            toggle_camera.setImageResource(newIconId)
//            disableFlash()
            hideTimer()
        } else {
            toast(R.string.camera_switch_error)
        }
    }

    private fun showLastMediaPreview() {
        if (mPreviewUri == null)
            return

        try {
            val REVIEW_ACTION = "com.android.camera.action.REVIEW"
            val intent = Intent(REVIEW_ACTION, mPreviewUri)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val intent = Intent(Intent.ACTION_VIEW, mPreviewUri)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                toast(R.string.no_gallery_app_available)
            }
        }
    }

//    private fun toggleFlash() {
//        if (!checkCameraAvailable()) {
//            return
//        }
//
//        mFlashlightState = ++mFlashlightState % if (mIsInPhotoMode) 3 else 2
//        checkFlash()
//    }
//
//    private fun checkFlash() {
//        when (mFlashlightState) {
//            FLASH_ON -> enableFlash()
//            FLASH_AUTO -> autoFlash()
//            else -> disableFlash()
//        }
//    }

//    private fun disableFlash() {
//        mPreview?.disableFlash()
//        toggle_flash.setImageResource(R.drawable.ic_flash_off)
//        mFlashlightState = FLASH_OFF
//        config.flashlightState = FLASH_OFF
//    }
//
//    private fun enableFlash() {
//        mPreview?.enableFlash()
//        toggle_flash.setImageResource(R.drawable.ic_flash_on)
//        mFlashlightState = FLASH_ON
//        config.flashlightState = FLASH_ON
//    }
//
//    private fun autoFlash() {
//        mPreview?.autoFlash()
//        toggle_flash.setImageResource(R.drawable.ic_flash_auto)
//        mFlashlightState = FLASH_AUTO
//        config.flashlightState = FLASH_AUTO
//    }

    private fun shutterPressed() {
        if (checkCameraAvailable()) {
            handleShutter()
        }
    }

    private fun handleShutter() {
        if (mIsInPhotoMode) {
            toggleBottomButtons(true)
            mPreview?.tryTakePicture()
        } else {
            if (mPreview?.toggleRecording() == true) {
                shutter.setImageDrawable(mRes.getDrawable(R.drawable.ic_video_stop))
                toggle_camera.beInvisible()
//                showTimer()
                shutter.isClickable = false
                handler.postDelayed(stopAfter1sec, 1100)
            } else {
                shutter.setImageDrawable(mRes.getDrawable(R.drawable.ic_video_rec))
                showToggleCameraIfNeeded()
                shutter.isClickable = true
//                hideTimer()
            }
        }
    }

    var handler: Handler = Handler()
    var stopAfter1sec: Runnable = Runnable { handleShutter() }

    fun toggleBottomButtons(hide: Boolean) {
        val alpha = if (hide) 0f else 1f
        shutter.animate().alpha(alpha).start()
        toggle_camera.animate().alpha(alpha).start()
//        toggle_flash.animate().alpha(alpha).start()

        shutter.isClickable = !hide
        toggle_camera.isClickable = !hide
//        toggle_flash.isClickable = !hide
    }

    private fun launchSettings() {
        if (settings.alpha == 1f) {
            val intent = Intent(applicationContext, SettingsActivity::class.java)
            startActivity(intent)
        } else {
            fadeInButtons()
        }
    }

//    private fun handleTogglePhotoVideo() {
//        togglePhotoVideo()
//    }

    private fun togglePhotoVideo() {
//        if (!hasRecordAudioPermission()) {
//            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION)
//            mIsAskingPermissions = true
//            return
//        }
//
//        if (!checkCameraAvailable()) {
//            return
//        }
//
        if (mIsVideoCaptureIntent)
            mPreview?.trySwitchToVideo()
//
////        disableFlash()
//        hideTimer()
////        mIsInPhotoMode = !mIsInPhotoMode
//        showToggleCameraIfNeeded()
//        checkButtons()
//        toggleBottomButtons(false)
    }

    private fun checkButtons() {
        if (mIsInPhotoMode) {
            initPhotoMode()
        } else {
            tryInitVideoMode()
        }
    }

    private fun initPhotoMode() {
//        toggle_photo_video.setImageDrawable(mRes.getDrawable(R.drawable.ic_video))
        shutter.setImageDrawable(mRes.getDrawable(R.drawable.ic_shutter))
        mPreview?.initPhotoMode()
        setupPreviewImage(true)
    }

    private fun tryInitVideoMode() {
        if (mPreview?.initRecorder() == true) {
            initVideoButtons()
        } else {
            if (!mIsVideoCaptureIntent) {
                toast(R.string.video_mode_error)
            }
        }
    }

    private fun initVideoButtons() {
//        toggle_photo_video.setImageDrawable(mRes.getDrawable(R.drawable.ic_camera))
        showToggleCameraIfNeeded()
        shutter.setImageDrawable(mRes.getDrawable(R.drawable.ic_video_rec))
//        checkFlash()
        setupPreviewImage(false)
    }

    private fun setupPreviewImage(isPhoto: Boolean) {
        val uri = if (isPhoto) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val lastMediaId = getLastMediaId(uri)
        if (lastMediaId == 0L) {
            return
        }
        mPreviewUri = Uri.withAppendedPath(uri, lastMediaId.toString())

        runOnUiThread {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed) {
                val options = RequestOptions()
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.NONE)

                Glide.with(this)
                        .load(mPreviewUri)
                        .apply(options)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(last_photo_video_preview)
            }
        }
    }

    private fun getLastMediaId(uri: Uri): Long {
        val projection = arrayOf(MediaStore.Images.ImageColumns._ID)
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC"
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, sortOrder)
            if (cursor?.moveToFirst() == true) {
                return cursor.getLongValue(MediaStore.Images.ImageColumns._ID)
            }
        } finally {
            cursor?.close()
        }
        return 0
    }

    private fun scheduleFadeOut() = mFadeHandler.postDelayed({ fadeOutButtons() }, FADE_DELAY.toLong())

    private fun fadeOutButtons() {
        fadeAnim(settings, .5f)
//        fadeAnim(toggle_photo_video, .0f)
        fadeAnim(change_resolution, .0f)
//        fadeAnim(last_photo_video_preview, .0f)
    }

    private fun fadeInButtons() {
        fadeAnim(settings, 1f)
//        fadeAnim(toggle_photo_video, 1f)
        fadeAnim(change_resolution, 1f)
//        fadeAnim(last_photo_video_preview, 1f)
        scheduleFadeOut()
    }

    private fun fadeAnim(view: View, value: Float) {
        view.animate().alpha(value).start()
        view.isClickable = value != .0f
    }

    private fun hideNavigationBarIcons() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
    }

    private fun hideTimer() {
        video_rec_curr_timer.text = 0.getFormattedDuration()
        video_rec_curr_timer.beGone()
        mCurrVideoRecTimer = 0
        mTimerHandler.removeCallbacksAndMessages(null)
    }

    private fun showTimer() {
        video_rec_curr_timer.beVisible()
        setupTimer()
    }

    private fun setupTimer() {
        runOnUiThread(object : Runnable {
            override fun run() {
                video_rec_curr_timer.text = mCurrVideoRecTimer++.getFormattedDuration()
                mTimerHandler.postDelayed(this, 1000)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraAndStoragePermission()) {
            resumeCameraItems()
            setupPreviewImage(false)
            scheduleFadeOut()
            mFocusRectView.setStrokeColor(config.primaryColor)

            if (mIsVideoCaptureIntent && mIsInPhotoMode) {

            }
            togglePhotoVideo()
            checkButtons()
            toggleBottomButtons(false)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (hasCameraAndStoragePermission()) {
            mOrientationEventListener.enable()
        }
    }

    private fun resumeCameraItems() {
        showToggleCameraIfNeeded()
        if (mPreview?.setCamera(mCurrCameraId) == true) {
            hideNavigationBarIcons()
//            checkFlash()

            if (!mIsInPhotoMode) {
                initVideoButtons()
            }
        } else {
            toast(R.string.camera_switch_error)
        }
    }

    private fun showToggleCameraIfNeeded() {
        toggle_camera.beInvisibleIf(Camera.getNumberOfCameras() <= 1)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!hasCameraAndStoragePermission() || mIsAskingPermissions)
            return

        mFadeHandler.removeCallbacksAndMessages(null)

        hideTimer()
        mPreview?.releaseCamera()
        mOrientationEventListener.disable()

        if (mPreview?.isWaitingForTakePictureCallback == true) {
            toast(R.string.photo_not_saved)
        }
    }

    private fun setupOrientationEventListener() {
        mOrientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed) {
                    mOrientationEventListener.disable()
                    return
                }

                val currOrient = if (orientation in 45..134) {
                    ORIENT_LANDSCAPE_RIGHT
                } else if (orientation in 225..314) {
                    ORIENT_LANDSCAPE_LEFT
                } else {
                    ORIENT_PORTRAIT
                }

                if (currOrient != mLastHandledOrientation) {
                    val degrees = when (currOrient) {
                        ORIENT_LANDSCAPE_LEFT -> 90
                        ORIENT_LANDSCAPE_RIGHT -> -90
                        else -> 0
                    }

                    animateViews(degrees)
                    mLastHandledOrientation = currOrient
                    mPreview?.deviceOrientationChanged()
                }
            }
        }
    }

    private fun animateViews(degrees: Int) {
        val views = arrayOf<View>(toggle_camera, change_resolution, shutter, settings, last_photo_video_preview)
        for (view in views) {
            rotate(view, degrees)
        }
    }

    private fun rotate(view: View, degrees: Int) = view.animate().rotation(degrees.toFloat()).start()

    private fun checkCameraAvailable(): Boolean {
        if (!mIsCameraAvailable) {
            toast(R.string.camera_unavailable)
        }
        return mIsCameraAvailable
    }

    fun finishActivity() {
        setResult(Activity.RESULT_OK)
        finish()
    }

//    override fun setFlashAvailable(available: Boolean) {
//        if (available) {
//            toggle_flash.beVisible()
//        } else {
//            toggle_flash.beInvisible()
//            disableFlash()
//        }
//    }

    override fun setIsCameraAvailable(available: Boolean) {
        mIsCameraAvailable = available
    }

    override fun videoSaved(uri: Uri) {
        setupPreviewImage(mIsInPhotoMode)
        if (mIsVideoCaptureIntent) {
            Intent().apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    override fun drawFocusRect(x: Int, y: Int) = mFocusRectView.drawFocusRect(x, y)

    override fun mediaSaved(path: String) {
        scanPath(path) {
            setupPreviewImage(mIsInPhotoMode)
        }

        if (isImageCaptureIntent()) {
            finishActivity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mPreview?.releaseCamera()
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(33, R.string.release_33))
            add(Release(35, R.string.release_35))
            add(Release(39, R.string.release_39))
            add(Release(44, R.string.release_44))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}