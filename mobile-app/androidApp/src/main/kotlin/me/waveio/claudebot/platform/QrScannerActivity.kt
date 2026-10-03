package me.waveio.claudebot.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import me.waveio.claudebot.R

/** Bundled decoder; no scanner app, network service, or saved camera frames. */
class QrScannerActivity : ComponentActivity() {
    private lateinit var scanner: BarcodeView
    private var completed = false
    private var previewResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val root = FrameLayout(this)
        scanner = BarcodeView(this).apply {
            decoderFactory = pairingQrDecoderFactory()
            contentDescription = getString(R.string.native_qr_prompt)
        }
        root.addView(scanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            val density = resources.displayMetrics.density
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            addView(TextView(this@QrScannerActivity).apply { setText(R.string.native_qr_prompt); setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER })
            addView(Button(this@QrScannerActivity).apply { setText(R.string.native_cancel); setOnClickListener { finish() } })
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        scanner.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                if (completed || !previewResumed || isFinishing || isDestroyed) return
                Toast.makeText(this@QrScannerActivity, R.string.native_camera_error, Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        scanner.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (completed || !previewResumed || isFinishing || isDestroyed) return
                completed = true
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QR, result.text))
                finish()
            }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        if (completed || isFinishing) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { finish(); return }
        previewResumed = true
        try { scanner.resume() } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.native_camera_error, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun finish() {
        // Cancel/back wins over a decode already queued on the main thread.
        completed = true
        previewResumed = false
        if (::scanner.isInitialized) { scanner.stopDecoding(); scanner.pause() }
        super.finish()
    }

    override fun onPause() { previewResumed = false; scanner.pause(); super.onPause() }
    override fun onDestroy() { previewResumed = false; scanner.stopDecoding(); scanner.pause(); super.onDestroy() }

    companion object { const val RESULT_QR = "qr_result" }
}
