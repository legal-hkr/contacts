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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.contacts.R
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

/** The contacts storage's thumbnail size (`DisplayPhoto.THUMBNAIL_MAX_DIM`, 96 px in AOSP): a photo shown larger is read from the full one. */
internal const val THUMBNAIL_PX = 96

/** Whether letter avatars take the palette in many colors (Settings › List, the owner's choice of 18 Nov 2026); provided by the shell. */
val LocalAvatarColors = staticCompositionLocalOf { true }

/** Calendar's palette without Graphite (fill, the color on it), in [Avatars.color]'s order; the widgets take it too. */
val AVATAR_PALETTE = listOf(
    R.color.avatar_red_fill to R.color.avatar_red_on, R.color.avatar_orange_fill to R.color.avatar_orange_on,
    R.color.avatar_amber_fill to R.color.avatar_amber_on, R.color.avatar_olive_fill to R.color.avatar_olive_on,
    R.color.avatar_green_fill to R.color.avatar_green_on, R.color.avatar_teal_fill to R.color.avatar_teal_on,
    R.color.avatar_sky_fill to R.color.avatar_sky_on, R.color.avatar_blue_fill to R.color.avatar_blue_on,
    R.color.avatar_violet_fill to R.color.avatar_violet_on, R.color.avatar_magenta_fill to R.color.avatar_magenta_on,
    R.color.avatar_raspberry_fill to R.color.avatar_raspberry_on,
)

/**
 * A contact's avatar (SPEC §4.5): the photo cropped to a circle, or the letters on a color chosen
 * by the lookup key, so a person keeps the color: one of Calendar's palette, or of the theme's three
 * container roles when Settings › List says so. The letters are [initials] (given and family name,
 * the owner, 18 Nov 2026), else the name's first letter.
 *
 * @param hint A photo known to show the same image (the list's thumbnail while the page reads the large one), shown until [photo] is read
 * @param large The full photo of a list's [photo] (its thumbnail): read instead when the avatar is larger than the thumbnail, which would show blurred and blocky (the owner, 26 Nov 2026)
 */
@Composable
fun Avatar(name: String, lookupKey: String, photo: String?, size: Dp, modifier: Modifier = Modifier, hint: String? = null, initials: String? = null, large: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // The storage's thumbnail is at most THUMBNAIL_PX square: a larger avatar reads the full photo, scaled down as it is decoded
    val fromLarge = large != null && px > THUMBNAIL_PX
    val source = if (fromLarge) large else photo
    val meanwhile = if (fromLarge) photo else hint
    // What is already in memory shows at once: no letter flashing before a photo read a moment ago
    // A full photo that can't be read falls back to the thumbnail, not to the letters (the code review of 27 Nov 2026)
    val image by produceState(source?.let { Photos.peek(it, px) } ?: meanwhile?.let { Photos.peek(it, px) }, source, px) {
        value = source?.let { Photos.load(context, it, px) } ?: if (fromLarge) photo?.let { Photos.load(context, it, px) } else null
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape))
        return
    }
    val colors = MaterialTheme.colorScheme
    val (fill, text) = if (LocalAvatarColors.current) {
        AVATAR_PALETTE[Avatars.color(lookupKey)].let { (f, t) -> colorResource(f) to colorResource(t) }
    } else {
        when (Avatars.tone(lookupKey)) {
            0 -> colors.primaryContainer to colors.onPrimaryContainer
            1 -> colors.secondaryContainer to colors.onSecondaryContainer
            else -> colors.tertiaryContainer to colors.onTertiaryContainer
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val letters = initials ?: Avatars.initial(name, locale)
    val style = MaterialTheme.typography.titleMedium
    // Two letters a little smaller, so the wider pair keeps clear of the circle's edge
    val fontSize = with(LocalDensity.current) { (size * if (letters.length > 1) 0.36f else 0.42f).toSp() }
    Box(modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Text(letters, style = style.copy(fontSize = fontSize, lineHeight = fontSize), color = text, maxLines = 1)
    }
}
