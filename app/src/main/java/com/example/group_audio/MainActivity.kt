
package com.example.group_audio

import ai.picovoice.android.voiceprocessor.VoiceProcessor
import ai.picovoice.android.voiceprocessor.VoiceProcessorException
import ai.picovoice.eagle.Eagle
import ai.picovoice.eagle.EagleActivationException
import ai.picovoice.eagle.EagleActivationLimitException
import ai.picovoice.eagle.EagleActivationRefusedException
import ai.picovoice.eagle.EagleActivationThrottledException
import ai.picovoice.eagle.EagleException
import ai.picovoice.eagle.EagleInvalidArgumentException
import ai.picovoice.eagle.EagleProfile
import ai.picovoice.eagle.EagleProfiler
import ai.picovoice.eagle.EagleProfilerEnrollFeedback
import ai.picovoice.eagle.EagleProfilerEnrollResult
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.telecom.Call
import android.text.Editable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import android.widget.Toolbar.LayoutParams
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.log
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity() {
    private val voiceProcessor = VoiceProcessor.getInstance()
    private val progressBarIds: MutableList<Int> = ArrayList()
    private val enrollmentPcm = ArrayList<Short>()
    private val profiles: MutableList<EagleProfile> = ArrayList()
    private val enableDump = false
    private var eagle: Eagle? = null
    private var eagleProfiler: EagleProfiler? = null
    private lateinit var smoothScores: FloatArray
    private var eagleDump: AudioDump? = null

    lateinit var errorText: TextView
    lateinit var recordingTextView: TextView
    lateinit var enrollButton: ToggleButton
    lateinit var enrollProgress: ProgressBar
    lateinit var testButton: ToggleButton
    lateinit var   speakerTableLayout: TableLayout
    lateinit var resetButton: Button
    lateinit var webView: WebView
    lateinit var editText: EditText
    lateinit var sendModeButton: ToggleButton

    var isSwitch = false
    var isTriggered = false


    var handler: Handler? = null

    private var isOn = false

    private val executor = Executors.newSingleThreadExecutor()
    private val executor2 = Executors.newSingleThreadExecutor()



    @SuppressLint("SetTextI18n")
    private fun setUIState(state: UIState) {
        runOnUiThread {
            Log.i("THREAD", Thread.currentThread().name)

            when (state) {
                UIState.IDLE -> {
                    if (profiles.size == 0) {
                        recordingTextView.text = "Enroll a speaker to start testing Voicii"
                    } else {
                        recordingTextView.text =
                            "- Press 'ENROLL' to add more speakers\n- 'TEST' to test voice recognition"
                    }
                    enrollButton.isEnabled = true
                    enrollProgress.visibility = View.GONE
                    testButton.isEnabled = profiles.size > 0
                }

                UIState.ENROLLING -> {
                    errorText.visibility = View.INVISIBLE
                    recordingTextView.text = "Start speaking to enroll speaker..."
                    enrollButton.isEnabled = false
                    enrollProgress.visibility = View.VISIBLE
                    testButton.isEnabled = false
                }

                UIState.INITIALIZING -> {
                    errorText.visibility = View.INVISIBLE
                    recordingTextView.text = "Initializing Eagle with current speakers..."
                    enrollButton.isEnabled = false
                    testButton.isEnabled = false
                }

                UIState.TESTING -> {
                    errorText.visibility = View.INVISIBLE
                    recordingTextView.text = "Identifying speaker..."
                    enrollButton.isEnabled = false
                    testButton.isEnabled = true
                }

                UIState.ERROR -> {
                    enrollButton.isEnabled = false
                    testButton.isChecked = false

                }

                else -> {}
            }
        }
    }

    private fun handleEagleException(e: EagleException) {
        if (e is EagleInvalidArgumentException) {
            displayError(
                String.format(
                    "%s\nEnsure your AccessKey '%s' is valid",
                    e.message,
                    ACCESS_KEY
                )
            )
        } else if (e is EagleActivationException) {
            displayError("AccessKey activation error")
        } else if (e is EagleActivationLimitException) {
            displayError("AccessKey reached its device limit")
        } else if (e is EagleActivationRefusedException) {
            displayError("AccessKey refused")
        } else if (e is EagleActivationThrottledException) {
            displayError("AccessKey has been throttled")
        } else {
            displayError("Failed to initialize Eagle " + e.message)
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(Color.WHITE)
        setSupportActionBar(toolbar)

        errorText = findViewById(R.id.errorTextView)
        recordingTextView = findViewById(R.id.recordingTextView)
        enrollButton = findViewById(R.id.enrollButton)
        enrollProgress = findViewById(R.id.enrollProgress)
        testButton = findViewById(R.id.testButton)
        speakerTableLayout = findViewById(R.id.speakerTableLayout)
        resetButton = findViewById(R.id.resetButton)
        editText = findViewById(R.id.EditText)
        sendModeButton = findViewById(R.id.sendModeButton)

        webView = findViewById(R.id.webView)
        webView.webViewClient = WebViewClient()
        webView.loadUrl("https://www.google.com")



        eagleDump = AudioDump(applicationContext, "eagle_demo.wav")


        val alertDialogBuilder = AlertDialog.Builder(this)
            .setTitle("EAGLE API IS INITIALIZING")
            .setMessage("initialization taking too long? well your internet connection might be too slow. Quit the app and try again later.")
            .setCancelable(false)
        val alertDialog = alertDialogBuilder.create()


        val submitDialog = AlertDialog.Builder(this)
            .setTitle("SUBMITTING REGISTRATIONS")
            .setMessage("please wait...")
            .setCancelable(false)
        val submitDialogCreate = submitDialog.create()



        //initialize eagle profiler
        CoroutineScope(IO).launch {

            runOnUiThread {
                alertDialog.show()
            }

            initializeEagleProfiler()

            runOnUiThread {
                alertDialog.hide()
            }
            runOnUiThread {
                if (eagle != null){
                    submitDialogCreate.hide()
                }
            }

        }

        voiceProcessor.addErrorListener { error: VoiceProcessorException ->
            runOnUiThread { displayError(error.toString()) }
        }


        enrollButton.setOnClickListener {
            if (eagleProfiler == null) {
                displayError("Eagle is not initialized")
                enrollButton.isChecked = false
                return@setOnClickListener
            }
            if (enrollButton.isChecked) {
                if (voiceProcessor.hasRecordAudioPermission(this)) {
                    setUIState(UIState.ENROLLING)
                    createSpeaker()
                    CoroutineScope(Default).launch {
                        startEnrolling()
                    }

                } else {
                    requestRecordPermission()
                }
            } else {
                setUIState(UIState.IDLE)

                stop()


            }
        }


        val refreshButton = findViewById<ImageButton>(R.id.refreshButton)
        val wifi: WifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager


        refreshButton.setOnClickListener {
            try {
                if (wifi.isWifiEnabled) {

                    val e = arrayOfNulls<EagleProfile>(profiles.size)
                    for (i in profiles.indices) {
                        e[i] = profiles[i]
                    }
                    Toast.makeText(this@MainActivity, "submitting registrations", Toast.LENGTH_SHORT).show()
                    eagle = Eagle.Builder()
                        .setSpeakerProfiles(e)
                        .setAccessKey(ACCESS_KEY)
                        .build(applicationContext)
                    smoothScores = FloatArray(profiles.size)

                } else {
                    val wifiDialog = AlertDialog.Builder(this)
                        .setTitle("WIFI IS NOT ENABLED")
                        .setMessage("enable wifi to verify system key. You can disable wifi after verifying your key")
                        .setPositiveButton("ok"
                        ) { p0, p1 ->
                            p0?.dismiss()
                        }
                    wifiDialog.create().show()
                }
            }
            catch (e: EagleException) {
                handleEagleException(e)
            }
        }




        testButton.setOnClickListener {
            if (eagle == null) {
                if (wifi.isWifiEnabled) {
                    val e = arrayOfNulls<EagleProfile>(profiles.size)
                    for (i in profiles.indices) {
                        e[i] = profiles[i]
                    }
//                    Toast.makeText(
//                        this@MainActivity,
//                        "submitting registrations",
//                        Toast.LENGTH_SHORT
//                    ).show()
                    submitDialogCreate.show()
                    eagle = Eagle.Builder()
                        .setSpeakerProfiles(e)
                        .setAccessKey(ACCESS_KEY)
                        .build(applicationContext)
                    smoothScores = FloatArray(profiles.size)

                    submitDialogCreate.hide()

                } else {
                    val wifiDialog = AlertDialog.Builder(this)
                        .setTitle("WIFI IS NOT ENABLED")
                        .setMessage("enable wifi to verify system key. You can disable wifi after verifying your key")
                        .setPositiveButton(
                            "ok"
                        ) { p0, p1 ->
                            p0?.dismiss()
                        }
                    wifiDialog.create().show()
                }
            }
            else {
                if (testButton.isChecked) {
                    try {
                        if (voiceProcessor.hasRecordAudioPermission(this)) {
                            setUIState(UIState.TESTING)
                            CoroutineScope(IO).launch {
                                startTesting()
                            }
                        } else {
                            requestRecordPermission()
                        }
                    } catch (e: EagleException) {
                        handleEagleException(e)
                    }
                } else {
                    setUIState(UIState.IDLE)
                    synchronized(voiceProcessor) {
                        stop()
//                    if (enableDump) {
//                        eagleDump!!.saveFile("eagle_test.wav")
//                    }

//                    eagle!!.delete()
//                    eagle = null
                    }
                    for (id in progressBarIds) {
                        val progressBar = findViewById<ProgressBar>(
                            id
                        )
                        progressBar.progress = 100
                    }
                }
            }
        }

        resetButton.setOnClickListener {

            stop()
            eagle = null

            if (enrollButton.isChecked) {
                enrollButton.performClick()
            }
            if (testButton.isChecked) {
                testButton.performClick()
            }
            speakerTableLayout.removeViews(2, progressBarIds.size)
            profiles.clear()
            progressBarIds.clear()
            setUIState(UIState.IDLE)
        }


    }

    private suspend fun initializeEagleProfiler(){
        try {
            val builder = EagleProfiler.Builder()
                .setAccessKey(ACCESS_KEY)
            eagleProfiler = builder.build(applicationContext)

        } catch (e: EagleException) {
            handleEagleException(e)
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        eagleProfiler!!.delete()
        eagle!!.delete()
        eagle = null
    }

    private fun displayError(message: String) {
        setUIState(UIState.ERROR)
        errorText.text = message
        errorText.visibility = View.VISIBLE
        enrollButton.isEnabled = false
        testButton.isEnabled = false
    }

    private suspend fun startEnrolling() {
        try {
            eagleProfiler!!.reset()
        } catch (e: EagleException) {
            displayError(
                """
                    Failed to reset Eagle
                    ${e.message}
                    """.trimIndent()
            )
            return
        }
        val frameLength = 15000
        enrollmentPcm.clear()
        enrollmentPcm.size
        voiceProcessor.addFrameListener { frame: ShortArray ->
            for (sample in frame) {
                enrollmentPcm.add(sample)
            }
            if (enrollmentPcm.size > eagleProfiler!!.minEnrollSamples) {
                val enrollFrame = ShortArray(enrollmentPcm.size)
                for (i in enrollmentPcm.indices) {
                    enrollFrame[i] = enrollmentPcm[i]
                }
                enrollmentPcm.clear()
                if (enableDump) {
                    eagleDump!!.add(enrollFrame)
                }
                CoroutineScope(Default).launch {
                    enrollSpeaker(enrollFrame)
                }
            }
        }
        try {
            voiceProcessor.start(frameLength, eagleProfiler!!.sampleRate)
        } catch (e: VoiceProcessorException) {
            displayError(
                """
                    Failed to start recording
                    ${e.message}
                    """.trimIndent()
            )
        }
    }

    private suspend fun startTestingCor() {
        println(Thread.currentThread().name)
        delay(1000)
    }


    private suspend fun startTesting() {
            voiceProcessor.addFrameListener { frame: ShortArray? ->
                try {
                    synchronized(voiceProcessor) {
                        if (eagle == null) {
                            return@addFrameListener
                        }
                        val scores = eagle!!.process(frame)
                        for (i in scores.indices) {
                            val alpha = 0.25f
                            smoothScores[i] =
                                alpha * smoothScores[i] + (1 - alpha) * scores[i]
                        }
                        if (enableDump) {
                            eagleDump!!.add(frame!!)
                        }
                    }

                    //submit on Main Thread
                    runOnUiThread {
                        if (progressBarIds.size == 0) {
                            return@runOnUiThread
                        }
                        for (i in smoothScores.indices) {
                            val progressBar =
                                findViewById<ProgressBar>(progressBarIds[i])
                            progressBar.progress = (smoothScores[i] * 100).roundToInt()
                        }

                        webPauseInterface.onSendRequest()

                    }
                } catch (e: EagleException) {
                    runOnUiThread {
                        displayError(
                            """
                        Failed to process audio
                        ${e.message}
                        """.trimIndent()
                        )
                    }
                }
            }
            try {
                voiceProcessor.start(eagle!!.frameLength, eagleProfiler!!.sampleRate)
            } catch (e: VoiceProcessorException) {
                runOnUiThread {
                    displayError(
                        """
                        Failed to start recording
                        ${e.message}
                        """.trimIndent()
                    )
                }
            }


    }

    private val webPauseInterface = object : WebPauseInterface {
        override fun onSendRequest() {
            val ip = editText.text.toString()

            //we need variable assignment in these form of if statements because progress bar size starts from nothing
            //if not been implemented this way the program would crash
            val speaker1Progress = if (progressBarIds.size >= 1) (smoothScores[0] * 100).roundToInt() else 0
            val speaker2Progress = if (progressBarIds.size >= 2) (smoothScores[1] * 100).roundToInt() else 0
            val speaker3Progress = if (progressBarIds.size >= 3) (smoothScores[2] * 100).roundToInt() else 0

            //probabilities
            //if 3 speakers are in 100 scores
            val sp1 = if (speaker1Progress >= 60) 1 else 0
            val sp2 = if (speaker2Progress >= 60) 1 else 0
            val sp3 = if (speaker3Progress >= 60) 1 else 0

            if (speaker1Progress >= 60 || speaker2Progress >= 60 || speaker3Progress >= 60) {

//                val speaker1 = "/speaker1/${speaker1Progress.toString().padStart(3, '0')}"
//                val speaker2 = "/speaker2/${speaker2Progress.toString().padStart(3, '0')}"
//                val speaker3 = "/speaker3/${speaker3Progress.toString().padStart(3, '0')}"
                if (sendModeButton.isChecked) {
                    if (!webView.url!!.contains("${ip}/speaker=${sp1}${sp2}${sp3}&light=on")){
                        webView.loadUrl("${ip}/speaker=${sp1}${sp2}${sp3}&light=on")
                    }
                }  else {
                    if (!webView.url!!.contains("${ip}/speaker=${sp1}${sp2}${sp3}&light=off")){
                        webView.loadUrl("${ip}/speaker=${sp1}${sp2}${sp3}&light=off")
                    }

                }

            }
        }

    }

    private  fun stop() {

        runOnUiThread {
            try {
                voiceProcessor.stop()
                voiceProcessor.clearFrameListeners()
            } catch (e: VoiceProcessorException) {
                displayError(
                    """
                    Failed to stop recording
                    ${e.message}
                    """.trimIndent()
                )
            }
        }

    }

    private fun requestRecordPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), 0
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        val enrollButton = findViewById<ToggleButton>(R.id.enrollButton)
//        val testButton = findViewById<ToggleButton>(R.id.testButton)
        if (grantResults.size == 0 || grantResults[0] == PackageManager.PERMISSION_DENIED) {
            if (enrollButton.isChecked) {
                enrollButton.toggle()
            }
            if (testButton.isChecked) {
                testButton.toggle()
            }
        } else {
            if (enrollButton.isChecked) {
                setUIState(UIState.ENROLLING)
                createSpeaker()
                CoroutineScope(Default).launch {
                    startEnrolling()
                }

            } else if (testButton.isChecked) {
                setUIState(UIState.TESTING)
                CoroutineScope(IO).launch {
                    startTesting()
                }

            }
        }
    }

//    @SuppressLint("SetTextI18n", "DefaultLocale")
//    fun onEnrollClick(view: View?) {
////        val enrollButton = findViewById<ToggleButton>(R.id.enrollButton)
//        if (eagleProfiler == null) {
//            displayError("Eagle is not initialized")
//            enrollButton.isChecked = false
//            return
//        }
//        if (enrollButton.isChecked) {
//            if (voiceProcessor.hasRecordAudioPermission(this)) {
//                setUIState(UIState.ENROLLING)
//                createSpeaker()
//                startEnrolling()
//            } else {
//                requestRecordPermission()
//            }
//        } else {
//            setUIState(UIState.IDLE)
//            stop()
//        }
//    }

//    @SuppressLint("SetTextI18n", "DefaultLocale")
//    fun onTestClick(view: View?) {
////        val testButton = findViewById<ToggleButton>(R.id.testButton)
//        if (testButton.isChecked) {
//            try {
//                val e = arrayOfNulls<EagleProfile>(profiles.size)
//                for (i in profiles.indices) {
//                    e[i] = profiles[i]
//                }
//                eagle = Eagle.Builder()
//                    .setSpeakerProfiles(e)
//                    .setAccessKey(ACCESS_KEY)
//                    .build(applicationContext)
//                smoothScores = FloatArray(profiles.size)
//                if (voiceProcessor.hasRecordAudioPermission(this)) {
//                    setUIState(UIState.TESTING)
//                    startTesting()
//                } else {
//                    requestRecordPermission()
//                }
//            } catch (e: EagleException) {
//                handleEagleException(e)
//            }
//        } else {
//            setUIState(UIState.IDLE)
//            synchronized(voiceProcessor) {
//                stop()
//                if (enableDump) {
//                    eagleDump!!.saveFile("eagle_test.wav")
//                }
//                eagle!!.delete()
//                eagle = null
//            }
//            for (id in progressBarIds) {
//                val progressBar = findViewById<ProgressBar>(
//                    id
//                )
//                progressBar.progress = 100
//            }
//        }
//    }

//    fun onResetClick(view: View?) {
//        stop()
////        val enrollButton = findViewById<ToggleButton>(R.id.enrollButton)
//        if (enrollButton.isChecked) {
//            enrollButton.performClick()
//        }
////        val testButton = findViewById<ToggleButton>(R.id.testButton)
//        if (testButton.isChecked) {
//            testButton.performClick()
//        }
////        val speakerTableLayout = findViewById<TableLayout>(R.id.speakerTableLayout)
//        speakerTableLayout.removeViews(1, progressBarIds.size)
//        profiles.clear()
//        progressBarIds.clear()
//        setUIState(UIState.IDLE)
//    }

    private fun getFeedback(feedback: EagleProfilerEnrollFeedback): String {
        return when (feedback) {
            EagleProfilerEnrollFeedback.AUDIO_OK -> "Enrolling speaker.."
            EagleProfilerEnrollFeedback.AUDIO_TOO_SHORT -> "Insufficient audio length"
            EagleProfilerEnrollFeedback.UNKNOWN_SPEAKER -> "Different speaker in audio"
            EagleProfilerEnrollFeedback.NO_VOICE_FOUND -> "Unable to detect voice in audio"
            EagleProfilerEnrollFeedback.QUALITY_ISSUE -> "Audio quality too low to use for enrollment"
            else -> "Unrecognized feedback"
        }
    }

    @SuppressLint("DefaultLocale")
    private fun createSpeaker() {
        runOnUiThread {
            val padding = (15 * this.resources.displayMetrics.density).toInt()
            val padding2 = (5 * this.resources.displayMetrics.density).toInt()
            val row = TableRow(this)
            row.setPadding(padding, padding2, padding, padding2)
            val params =
                TableRow.LayoutParams()
            params.weight = 1f
            params.gravity = Gravity.CENTER_VERTICAL

            val speakerText = TextView(this)
            speakerText.text = String.format("Speaker %d", profiles.size + 1)
            speakerText.setTextColor(Color.WHITE)
            speakerText.layoutParams = params
            speakerText.gravity = Gravity.CENTER_VERTICAL
            speakerText.setTypeface(null, Typeface.BOLD)
            speakerText.setTextColor(ContextCompat.getColorStateList(this, R.color.shadowColorAccent))

            val progressBar =
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            progressBar.progressDrawable = ContextCompat.getDrawable(this, R.drawable.custom_progress)
            progressBar.scaleY = 0.5f
            val id = LinearLayout.generateViewId()
            progressBar.id = id
            progressBar.layoutParams = params
            progressBar.setPadding(padding, 0, padding, 0)
            progressBar.progressDrawable
                .setColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY)
            progressBarIds.add(id)
            row.addView(speakerText)
            row.addView(progressBar)
            val speakerTableLayout =
                findViewById<TableLayout>(R.id.speakerTableLayout)
            speakerTableLayout.addView(row)
        }
    }





    private suspend fun setVoiceProgress(result: EagleProfilerEnrollResult) {
        withContext(Main) {
            if (progressBarIds.size == 0) {
                return@withContext
            }
            val progressBar =
                findViewById<ProgressBar>(progressBarIds[progressBarIds.size - 1])
            progressBar.progress = Math.round(result.percentage)
            //                    val enrollButton = findViewById<ToggleButton>(R.id.enrollButton)
            enrollButton.performClick()
        }
    }

    @SuppressLint("DefaultLocale")
    private suspend fun enrollSpeaker(enrollFrame: ShortArray) {

            try {
                Log.i("THREAD", Thread.currentThread().name)
                val result = eagleProfiler!!.enroll(enrollFrame)
                if (result.feedback == EagleProfilerEnrollFeedback.AUDIO_OK && result.percentage == 100f) {
                    stop()
                    val profile = eagleProfiler!!.export()
                    profiles.add(profile)

                    //on MainThread
                    setVoiceProgress(result)


                    if (enableDump) {
                        eagleDump!!.saveFile(
                            String.format(
                                "eagle_enroll_speaker_%d.wav",
                                profiles.size
                            )
                        )
                    }
                } else {
                    val finalMessage = String.format(
                        "%s. Keep speaking until the enrollment percentage reaches 100%%.",
                        getFeedback(result.feedback)
                    )
                    runOnUiThread {
                        if (progressBarIds.size == 0) {
                            return@runOnUiThread
                        }
                        val progressBar =
                            findViewById<ProgressBar>(progressBarIds[progressBarIds.size - 1])
                        progressBar.progress = Math.round(result.percentage)
                        //                    val recordingTextView =
                        //                        findViewById<TextView>(R.id.recordingTextView)
                        recordingTextView.text = finalMessage
                    }
                }
            } catch (e: EagleException) {
                runOnUiThread {
                    displayError(
                        """
                            Failed to enroll
                            ${e.message}
                            """.trimIndent()
                    )
                }
            }

    }

    private enum class UIState {
        IDLE, ENROLLING, INITIALIZING, TESTING, ERROR
    }

    companion object {
        private const val ACCESS_KEY = "qH6y5CuquibcBzM9SB44UAGwQXHAIAFTvEbbNWhMhFvdPFetOCk/5g=="
    }
}