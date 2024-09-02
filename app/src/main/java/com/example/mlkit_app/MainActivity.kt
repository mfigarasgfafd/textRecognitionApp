package com.example.mlkit_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.mlkit_app.ui.theme.MLKIT_appTheme
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import java.util.concurrent.Executors
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.ui.text.AnnotatedString


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MLKIT_appTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val textResult = remember { mutableStateOf("Recognized text will appear here") }
    val isCameraFrozen = remember { mutableStateOf(false) }
    val frozenBitmap = remember { mutableStateOf<Bitmap?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        CameraPreview(
            textResult = textResult,
            isCameraFrozen = isCameraFrozen,
            frozenBitmap = frozenBitmap
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextViewPlaceholder(text = textResult.value)
        CopyTextButton(text = textResult.value)
    }
}

@Composable
fun TextViewPlaceholder(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(16.dp)
    )
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(
    textResult: MutableState<String>,
    isCameraFrozen: MutableState<Boolean>,
    frozenBitmap: MutableState<Bitmap?>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val cameraProvider = remember { cameraProviderFuture.get() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val analysisUseCase = remember {
        ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    fun bindCameraUseCases() {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        analysisUseCase.setAnalyzer(executor) { imageProxy ->
            if (!isCameraFrozen.value) {
                processImageProxy(textRecognizer, imageProxy, textResult)
            } else {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            analysisUseCase
        )
    }

    DisposableEffect(Unit) {
        cameraProviderFuture.addListener({
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider.unbindAll()
            executor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clickable {
                if (!isCameraFrozen.value) {
                    // Freeze the camera and capture the frame as bitmap
                    frozenBitmap.value = previewView.bitmap
                    isCameraFrozen.value = true
                    frozenBitmap.value?.let { bitmap ->
                        val image = InputImage.fromBitmap(bitmap, 0)
                        textRecognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                textResult.value = visionText.text
                            }
                            .addOnFailureListener {
                                // Handle errors
                            }
                    }
                    cameraProvider.unbindAll() // Unbind the camera to freeze the frame
                } else {
                    // Unfreeze the camera by re-binding lifecycle and use cases
                    isCameraFrozen.value = false
                    bindCameraUseCases() // Re-bind camera to re-enable analysis
                }
            }
    ) {
        if (isCameraFrozen.value && frozenBitmap.value != null) {
            Image(
                bitmap = frozenBitmap.value!!.asImageBitmap(),
                contentDescription = "Frozen Frame",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}


@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    textRecognizer: com.google.mlkit.vision.text.TextRecognizer,
    imageProxy: ImageProxy,
    textResult: MutableState<String>
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                textResult.value = visionText.text // Update recognized text
            }
            .addOnFailureListener {
                // Handle errors
            }
            .addOnCompleteListener {
                imageProxy.close() // Close image proxy
            }
    }
}


@Composable
fun CopyTextButton(text: String) {
    val clipboardManager = LocalClipboardManager.current

    Button(
        onClick = {
            clipboardManager.setText(AnnotatedString(text))
        },
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Text(text = "Copy Text")
    }
}