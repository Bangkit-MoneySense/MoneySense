package com.application.moneysense.ui.moneyscan

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.application.moneysense.ImageAnalyzer
import com.application.moneysense.R
import com.application.moneysense.ViewModelFactory
import com.application.moneysense.data.pref.UserPreferences
import com.application.moneysense.data.pref.dataStore
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.application.moneysense.data.model.PredictResponse
import com.application.moneysense.databinding.ActivityMoneyScanBinding

class MoneyScan : AppCompatActivity() {

    companion object {
        private const val REQUIRED_PERMISSION = Manifest.permission.CAMERA
        private const val CAMERA_PERMISSION_CODE = 101
    }

    private lateinit var binding: ActivityMoneyScanBinding
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMoneyScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        try {
            // Check permissions and start camera
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                requestPermissionLauncher.launch(REQUIRED_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e("MoneyScan", "Error in onCreate: ${e.message}", e)
            showToast("Initialization failed. Please restart the app.")
        }

        val switchTheme = findViewById<FloatingActionButton>(R.id.switchModeFAB)

        val pref = UserPreferences.getInstance(application.dataStore)
        val moneyViewModel = ViewModelProvider(this, ViewModelFactory(pref)).get(
            MoneyScanViewModel::class.java)

        // logic to switch between dark and light mode
        moneyViewModel.getThemeSettings().observe(this) { isDarkModeActive: Boolean ->
            if (isDarkModeActive) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                //change Tint color of switch button
                switchTheme.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
                switchTheme.setImageResource(R.drawable.ic_dark_mode)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                switchTheme.setImageResource(R.drawable.ic_light_mode)
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()


        // checking the permission to use camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }

        switchTheme.setOnClickListener() {
            val isDarkModeActive = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_NO
            moneyViewModel.saveThemeSetting(isDarkModeActive)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                showToast("Permission request granted")
            } else {
                showToast("Permission request denied")
            }
        }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(
            this,
            REQUIRED_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ============================================
    // ==========camerax section function==========
    // ============================================
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalyzer { response: PredictResponse ->
                        response.data?.let { predictionData ->
                            displayPredictionData(
                                authenticity = predictionData.authenticity,
                                authenticityConfidence = predictionData.authenticityConfidence,
                                nominalConfidence = predictionData.nominalConfidence,
                                amount = predictionData.currency
                            )
                        }
                    })
                }

            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cvRealtime.surfaceProvider)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("MoneyScan", "Error binding use cases", exc)
                showToast("Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun displayPredictionData(authenticity: String?, authenticityConfidence: Double?, nominalConfidence: Double?, amount: String?) {
        binding.tvReview.text = authenticity
        binding.tvAuthenticityValue.text = authenticityConfidence?.let { "%.2f".format(it * 100) } ?: "N/A"
        binding.tvNominalRatingValue.text = nominalConfidence?.let { "%.2f".format(it * 100) } ?: "N/A"
        binding.tvAmount.text = amount
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}