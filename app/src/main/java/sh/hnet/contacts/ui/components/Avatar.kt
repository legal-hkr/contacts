package sh.hnet.contacts.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.contacts.model.Avatars

/** Photos already read, by address and size in pixels; small enough to keep a screenful and some scrolling. */
private object Photos {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** A photo already in memory, without reading anything. */
    fun peek(address: String, px: Int): ImageBitmap? = cache.get("$address@$px")?.asImageBitmap()

    suspend fun load(context: Context, address: String, px: Int): ImageBitmap? {
        val key = "$address@$px"
        cache.get(key)?.let { return it.asImageBitmap() }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                // The fragment only tells one photo from the next (ContactSource.photoAddress); the storage gets the address
                context.contentResolver.loadThumbnail(address.substringBefore('#').toUri(), Size(px, px), null)
            } catch (_: Exception) {
                // Gone meanwhile (a sync), or not readable: the letter avatar stands in
                null
            }
        } ?: return null
        cache.put(key, bitmap)
        return bitmap.asImageBitmap()
    }
}

/**
 * A contact's avatar (SPEC §4.5): the photo cropped to a circle, or the first letter of the name on
 * one of three theme container roles chosen by the lookup key, so a person keeps the color.
 *
 * @param hint A photo known to show the same image (the list's thumbnail while the page reads the large one), shown until [photo] is read
 */
@Composable
fun Avatar(name: String, lookupKey: String, photo: String?, size: Dp, modifier: Modifier = Modifier, hint: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // What is already in memory shows at once: no letter flashing before a photo read a moment ago
    val image by produceState(photo?.let { Photos.peek(it, px) } ?: hint?.let { Photos.peek(it, px) }, photo, px) { value = photo?.let { Photos.load(context, it, px) } }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape))
        return
    }
    val colors = MaterialTheme.colorScheme
    val (fill, text) = when (Avatars.tone(lookupKey)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    val locale = LocalConfiguration.current.locales[0]
    val style = MaterialTheme.typography.titleMedium
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Text(Avatars.initial(name, locale), style = style.copy(fontSize = fontSize, lineHeight = fontSize), color = text, maxLines = 1)
    }
}
