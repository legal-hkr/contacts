package sh.hnet.contacts.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

/** Photos from files (SPEC §10.4): any format the platform decodes, as a JPEG whose longer side is at most the size asked for. */
object ImageCodec {
    private const val QUALITY = 90

    /** Null when the bytes aren't an image the platform knows. */
    fun jpeg(bytes: ByteArray, maxSide: Int): ByteArray? = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val longer = max(info.size.width, info.size.height)
            if (longer > maxSide) decoder.setTargetSize(info.size.width * maxSide / longer, info.size.height * maxSide / longer)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }
}
