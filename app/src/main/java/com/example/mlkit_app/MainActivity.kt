package com.example.mlkit_app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.core.impl.utils.ContextUtil.getBaseContext
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.mlkit_app.ui.theme.MLKIT_appTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors


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
    val context = LocalContext.current

    val textResult = remember { mutableStateOf("Recognized text will appear here") }
    val isCameraFrozen = remember { mutableStateOf(false) }
    val frozenBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val useDocumentScanner = remember { mutableStateOf(false) }
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result -> handleDocumentScanningResult(result, textResult, context) }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        CameraPreview(
            textResult = textResult,
            isCameraFrozen = isCameraFrozen,
            frozenBitmap = frozenBitmap,
            useDocumentScanner = useDocumentScanner,
            scannerLauncher = scannerLauncher
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
@RequiresApi(Build.VERSION_CODES.Q)
fun savePdfToDocumentsScopedStorage(context: Context, pdfData: ByteArray, fileName: String): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.pdf")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { outputStream ->
            outputStream.write(pdfData)
        }
    }

    return uri
}
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(
    textResult: MutableState<String>,
    isCameraFrozen: MutableState<Boolean>,
    frozenBitmap: MutableState<Bitmap?>,
    useDocumentScanner: MutableState<Boolean>,
    scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
    val context = LocalContext.current  // Get the context here
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
                    if (useDocumentScanner.value) {
                        launchDocumentScanner(context, scannerLauncher)
                    } else {
                        frozenBitmap.value?.let { bitmap ->
                            val image = InputImage.fromBitmap(bitmap, 0)
                            processBitmapForTextRecognizer(textRecognizer, image, textResult)
                        }
                    }
                    cameraProvider.unbindAll() // Unbind the camera to freeze the frame
                } else {
                    // Unfreeze the camera by re-binding the lifecycle and use cases
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

        // Add the "Docu Scanner" button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Button(onClick = {
                useDocumentScanner.value = !useDocumentScanner.value
            }) {
                Text(if (useDocumentScanner.value) "Text Scanner" else "Docu Scanner")
            }
        }
    }
}

fun savePdfToDocuments(context: Context, pdfData: ByteArray, fileName: String): File? {
    // Get the "Documents" directory
    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    // Create the file object
    val pdfFile = File(documentsDir, "$fileName.pdf")

    return try {
        // Write the PDF data to the file
        FileOutputStream(pdfFile).use { fos ->
            fos.write(pdfData)
        }
        pdfFile
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

private fun handleDocumentScanningResult(
    activityResult: ActivityResult,
    textResult: MutableState<String>,
    context: android.content.Context,

    ) {
    val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
    if (activityResult.resultCode == Activity.RESULT_OK && result != null) {
        result.pdf?.uri?.let { pdfUri ->
            val inputStream = context.contentResolver.openInputStream(pdfUri)
            val pdfData = inputStream?.readBytes()
            inputStream?.close()

            pdfData?.let {
                // save to documents with current time as name
                val currentTime = System.currentTimeMillis()

                val savedPdfFile = savePdfToDocuments(context, it, currentTime.toString())
                if (savedPdfFile != null) {
                    // File saved successfully
                } else {
                    // Handle the error
                }
            }
        }
    } else if (activityResult.resultCode == Activity.RESULT_CANCELED) {
        // Handle cancellation
    } else {
        // Handle errors
    }
}

private fun launchDocumentScanner(
    context: android.content.Context,
    scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
    val options = GmsDocumentScannerOptions.Builder()
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
        .setGalleryImportAllowed(true)  // Enable gallery import if needed
        .build()

    GmsDocumentScanning.getClient(options)
        .getStartScanIntent(context as Activity)
        .addOnSuccessListener { intentSender ->
            scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
        .addOnFailureListener { e ->
            // Handle errors here
        }
}

private fun processBitmapForTextRecognizer(
    textRecognizer: com.google.mlkit.vision.text.TextRecognizer,
    image: InputImage,
    textResult: MutableState<String>
) {
    textRecognizer.process(image)
        .addOnSuccessListener { visionText ->
            textResult.value = visionText.text // Update the recognized text
        }
        .addOnFailureListener {
            // Handle any errors
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
                textResult.value = visionText.text // Update the recognized text
            }
            .addOnFailureListener {
                // Handle any errors
            }
            .addOnCompleteListener {
                imageProxy.close() // Close the image proxy
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