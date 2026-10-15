package org.ultimatepdf.ui.sign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.model.SignatureShape
import org.ultimatepdf.core.signature.SignatureImages
import java.io.File

/** Starts for turning a photo of ink on paper (a signature or a stamp) into a vector shape. */
class VectorizeFlow internal constructor(val fromCamera: () -> Unit, val fromGallery: () -> Unit)

/**
 * Photo → vector flow shared by signatures and stamps: takes a picture with the camera or
 * picks one from the gallery, shows the framing dialog and reports the traced shape.
 * The dialog is emitted where this is called. [isStamp] only changes the wording.
 */
@Composable
fun rememberVectorizeFlow(
    isStamp: Boolean,
    colors: List<Color>,
    color: Color,
    onColorChange: (Color) -> Unit,
    onShape: (SignatureShape) -> Unit
): VectorizeFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val currentOnShape by rememberUpdatedState(onShape)
    val currentOnColor by rememberUpdatedState(onColorChange)

    fun open(uri: Uri) {
        scope.launch {
            val bitmap = SignatureImages.loadUpright(context, uri)
            if (bitmap != null) photo = bitmap else Toast.makeText(context, R.string.sig_image_error, Toast.LENGTH_SHORT).show()
        }
    }

    val captureFile = remember { File(context.cacheDir, "vectorize_capture.jpg") }
    val captureUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", captureFile) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) open(captureUri) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture.launch(captureUri) else Toast.makeText(context, R.string.sig_camera_permission, Toast.LENGTH_LONG).show()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::open) }

    photo?.let { bitmap ->
        SignatureCaptureDialog(
            photo = bitmap,
            colors = colors,
            initialColor = color,
            isStamp = isStamp,
            onDismiss = { photo = null },
            onConfirm = { shape, chosen ->
                photo = null
                currentOnColor(chosen)
                currentOnShape(shape)
            }
        )
    }

    return remember(takePicture, cameraPermission, pickImage) {
        VectorizeFlow(
            fromCamera = {
                // The app declares CAMERA (for the scanner), so the system camera also needs it granted.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    takePicture.launch(captureUri)
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
            fromGallery = { pickImage.launch("image/*") }
        )
    }
}
