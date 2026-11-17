@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.contacts.R
import sh.hnet.contacts.ui.components.TooltipIconButton

private val CIRCLE = 300.dp
private const val OUTPUT = 720
private const val MAX_DECODE = 2048

/** What is cropped: a picked image, the contact's stored photo (both by address), or a crop made a moment ago. */
sealed interface CropSource {
    data class Address(val uri: Uri) : CropSource

    class Bytes(val jpeg: ByteArray) : CropSource
}

/** The image, decoded no larger than [MAX_DECODE] on its long side; null when it can't be read. */
private suspend fun decode(context: Context, source: CropSource): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val image = when (source) {
            is CropSource.Address -> ImageDecoder.createSource(context.contentResolver, source.uri)
            is CropSource.Bytes -> ImageDecoder.createSource(ByteBuffer.wrap(source.jpeg))
        }
        ImageDecoder.decodeBitmap(image) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > MAX_DECODE) decoder.setTargetSampleSize(long / MAX_DECODE + 1)
            // Software memory: the crop draws from it
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Crop the photo (SPEC §7.3): the image under a 300 dp ring, pinch to zoom and drag to move, the
 * image always covering the ring; outside it nothing is dimmed. Done makes a square JPEG of 720 px,
 * quality 90, kept in memory only until Save.
 */
@Composable
fun CropScreen(source: CropSource, onDone: (ByteArray) -> Unit, onClose: () -> Unit, onUnreadable: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image by produceState<Bitmap?>(null, source) {
        value = decode(context, source)
        if (value == null) onUnreadable()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var working by remember { mutableStateOf(false) }
    val circlePx = with(LocalDensity.current) { CIRCLE.toPx() }
    // Back leaves the crop, not the editor under it
    BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.title_crop_photo), maxLines = 1) },
                navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.button_close), onClose) },
                actions = {
                    Button(
                        enabled = image != null && !working,
                        onClick = {
                            val bitmap = image ?: return@Button
                            working = true
                            scope.launch {
                                val jpeg = withContext(Dispatchers.Default) { crop(bitmap, circlePx, scale, offset) }
                                working = false
                                onDone(jpeg)
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text(stringResource(R.string.button_done)) }
                },
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bitmap = image
                if (bitmap == null) {
                    LoadingIndicator()
                    return@Box
                }
                // At scale 1 the image's shorter side fills the ring
                val base = circlePx / min(bitmap.width, bitmap.height)
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                val ring = MaterialTheme.colorScheme.outline
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                val width = bitmap.width * base * scale
                                val height = bitmap.height * base * scale
                                val maxX = (width - circlePx) / 2
                                val maxY = (height - circlePx) / 2
                                offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            // Larger than the screen as the image may be: not clamped to it
                            .requiredSize(with(LocalDensity.current) { (bitmap.width * base).toDp() }, with(LocalDensity.current) { (bitmap.height * base).toDp() })
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                    Canvas(Modifier.size(CIRCLE)) {
                        drawCircle(ring, radius = size.minDimension / 2, style = Stroke(width = 2.dp.toPx()))
                    }
                    if (working) LoadingIndicator()
                }
            }
        }
    }
}

/** The square under the ring, at [OUTPUT] px, as JPEG. */
private fun crop(bitmap: Bitmap, circlePx: Float, scale: Float, offset: Offset): ByteArray {
    val factor = circlePx / min(bitmap.width, bitmap.height) * scale
    // The ring's corner in the image's pixels: the image is centered, moved by the offset
    val left = (bitmap.width * factor / 2 - offset.x - circlePx / 2) / factor
    val top = (bitmap.height * factor / 2 - offset.y - circlePx / 2) / factor
    val side = circlePx / factor
    val x = left.toInt().coerceIn(0, bitmap.width - 1)
    val y = top.toInt().coerceIn(0, bitmap.height - 1)
    val s = side.toInt().coerceAtMost(min(bitmap.width - x, bitmap.height - y)).coerceAtLeast(1)
    val square = Bitmap.createBitmap(bitmap, x, y, s, s)
    val scaled = square.scale(OUTPUT, OUTPUT)
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }
}
