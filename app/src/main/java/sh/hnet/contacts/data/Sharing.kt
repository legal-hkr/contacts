package sh.hnet.contacts.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.contacts.model.CardContact
import sh.hnet.contacts.model.VCardWriter

/**
 * Contacts as a vCard file for other apps (SPEC §11.4): written to the app's cache, `share/`, and
 * handed over through the app's FileProvider; the folder is emptied when the app starts next.
 */
object Sharing {
    /** The vCard MIME type; mail apps and contact apps know it (`text/x-vcard` is its older name). */
    const val MIME = "text/vcard"

    private fun folder(context: Context) = File(context.cacheDir, "share")

    fun clear(context: Context) {
        folder(context).listFiles()?.forEach { it.delete() }
    }

    /** A file name without the characters file systems or apps choke on. */
    fun fileName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "contact" }.take(80) + ".vcf"

    /** Writes [contacts] to a file named [fileName] in the share folder; its address for other apps. */
    suspend fun write(context: Context, contacts: List<CardContact>, fileName: String): Uri = withContext(Dispatchers.IO) {
        val folder = folder(context).apply { mkdirs() }
        val file = File(folder, fileName)
        file.writeText(VCardWriter.write(contacts))
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    /** The system's share sheet for [uri]. */
    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes [contacts] to a file the user picked (Save as). */
    suspend fun save(context: Context, contacts: List<CardContact>, target: Uri) = withContext(Dispatchers.IO) {
        val text = VCardWriter.write(contacts)
        (context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
    }
}
