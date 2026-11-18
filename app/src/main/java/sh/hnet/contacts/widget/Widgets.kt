package sh.hnet.contacts.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.MainActivity
import sh.hnet.contacts.R
import sh.hnet.contacts.model.Avatars
import sh.hnet.contacts.model.ContactList
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.FavoritesGrid
import sh.hnet.contacts.model.favorites
import sh.hnet.contacts.ui.components.AVATAR_PALETTE

/** What a tap on a widget asks the app for, carried as the intent's address so that each tap's intent is its own. */
sealed interface WidgetRequest {
    /** A contact's page, by its lookup key, which survives joins and syncs. */
    data class Contact(val lookupKey: String, val id: Long) : WidgetRequest

    /** The list: it shows its own state when access is missing. */
    data object Open : WidgetRequest

    companion object {
        const val SCHEME = "contacts-widget"

        fun of(address: Uri?): WidgetRequest? {
            if (address?.scheme != SCHEME) return null
            return when (address.host) {
                "contact" -> address.getQueryParameter("lookup")?.takeIf { it.isNotEmpty() }?.let { Contact(it, address.getQueryParameter("id")?.toLongOrNull() ?: 0) }
                "open" -> Open
                else -> null
            }
        }

        fun address(request: WidgetRequest): Uri = Uri.Builder().scheme(SCHEME).apply {
            when (request) {
                is Contact -> authority("contact").appendQueryParameter("lookup", request.lookupKey).appendQueryParameter("id", request.id.toString())
                Open -> authority("open")
            }
        }.build()
    }
}

/**
 * The home-screen widgets (SPEC §14; stage S9: Favorites), plain remote views as Calendar's. Favorites are the list's favorites row: the starred contacts of shown accounts in the list's order, under the same short names, with the same avatars – the letter avatars' colors follow Settings › List. Colors are resources the launcher resolves, so a change of theme needs no redraw. Texts are set here: a layout's own strings would come out in the launcher's language rather than the app's.
 *
 * Redrawn when the contacts change, the app's process closed too ([ChangeJob]), when the app starts and leaves the screen (Settings › List, accounts shown, the language), and when the launcher asks or a widget is resized; never by a period.
 */
object Widgets {
    /** Asked for a redraw; several requests waiting become one. */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val waiting = ConcurrentLinkedQueue<() -> Unit>()
    private val started = AtomicBoolean(false)

    /**
     * Redraws every placed widget. The redraws run one after another, never two at once, and requests that come while one runs are met by a single redraw after it.
     *
     * @param done Called once a redraw that started after this request has finished: a broadcast receiver or a job keeps its process until then
     */
    fun update(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext as ContactsApplication
        done?.let { waiting += it }
        if (started.compareAndSet(false, true)) {
            app.appScope.launch {
                for (request in requests) {
                    // Those asked before this redraw starts are answered by it
                    val answered = generateSequence { waiting.poll() }.toList()
                    try {
                        draw(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Drawn again at the next request; a widget must never take the app down
                    } finally {
                        answered.forEach { runCatching(it) }
                    }
                }
            }
        }
        requests.trySend(Unit)
    }

    /** As [update], returning when the redraw is done: a job that started it ends only then. */
    suspend fun updateAndWait(context: Context) = suspendCancellableCoroutine { continuation -> update(context) { continuation.resume(Unit) } }

    /** Whether any widget of the app is on a home screen. */
    fun placed(context: Context): Boolean = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, FavoritesWidget::class.java)).isNotEmpty()

    private suspend fun draw(app: ContactsApplication) {
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, FavoritesWidget::class.java))
        if (ids.isEmpty()) {
            ChangeJob.cancel(app)
            return
        }
        val access = app.contacts.hasAccess()
        if (access) ChangeJob.ensure(app)
        val settings = app.preferences.listSettings.first()
        val locale = app.resources.configuration.locales[0]
        val favorites = if (access) ContactList.favorites(app.contacts.contacts(settings, app.preferences.hiddenAccounts.first()).contacts, locale) else emptyList()

        val fits = ids.associateWith { manager.getAppWidgetOptions(it).let { options -> FavoritesGrid.fit(options.width(), options.height()) } }
        // Each photo read once, at the size the largest avatar of any widget needs
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (fits.values.maxOfOrNull { it.avatar } ?: FavoritesGrid.AVATAR).toFloat(), app.resources.displayMetrics).toInt()
        val wanted = favorites.take(fits.values.maxOfOrNull { it.count } ?: 0)
        val photos = withContext(Dispatchers.IO) { wanted.mapNotNull { row -> row.photo?.let { address -> photo(app, address, px)?.let { address to it } } }.toMap() }

        ids.forEach { id ->
            val fit = fits.getValue(id)
            val views = when {
                !access -> state(app, fit, access = false)
                favorites.isEmpty() -> state(app, fit, access = true)
                else -> grid(app, fit, favorites.take(fit.count), photos, settings.avatarColors, locale)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /** The widget's width and height in dp as the launcher reports them in portrait, or a 4 × 2 widget's when it doesn't. */
    private fun Bundle.width(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250

    private fun Bundle.height(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180

    /** Favorites (§14.1): the title where it fits, then the rows; the last row's empty cells keep the columns in place. */
    private fun grid(context: Context, fit: FavoritesGrid.Fit, contacts: List<ContactRow>, photos: Map<String, Bitmap>, manyColors: Boolean, locale: Locale): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_favorites).apply {
            setViewVisibility(R.id.title, if (fit.title) View.VISIBLE else View.GONE)
            setTextViewText(R.id.title, context.getString(R.string.title_widget_favorites))
            setOnClickPendingIntent(R.id.title, open(context, WidgetRequest.Open))
            removeAllViews(R.id.grid)
            contacts.chunked(fit.columns).forEachIndexed { index, row ->
                val line = RemoteViews(context.packageName, R.layout.widget_favorites_row)
                if (index > 0) line.setViewLayoutMargin(R.id.row, RemoteViews.MARGIN_TOP, FavoritesGrid.GAP.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                for (column in 0 until fit.columns) line.addView(R.id.row, cell(context, fit, row.getOrNull(column), photos, manyColors, locale))
                addView(R.id.grid, line)
            }
        }

    private fun cell(context: Context, fit: FavoritesGrid.Fit, contact: ContactRow?, photos: Map<String, Bitmap>, manyColors: Boolean, locale: Locale): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_favorites_cell).apply {
            if (contact == null) {
                setViewVisibility(R.id.cell, View.INVISIBLE)
                return@apply
            }
            if (fit.avatar != FavoritesGrid.AVATAR) {
                setViewLayoutWidth(R.id.avatar, fit.avatar.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(R.id.avatar, fit.avatar.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
            val photo = contact.photo?.let { photos[it] }
            if (photo != null) {
                setViewVisibility(R.id.avatar_fill, View.GONE)
                setViewVisibility(R.id.avatar_letters, View.GONE)
                setViewVisibility(R.id.avatar_photo, View.VISIBLE)
                setImageViewBitmap(R.id.avatar_photo, photo)
            } else {
                val (fill, on) = avatarColors(contact.lookupKey, manyColors)
                val letters = contact.initials ?: Avatars.initial(contact.name, locale)
                setColorStateList(R.id.avatar_fill, "setImageTintList", fill)
                setTextViewText(R.id.avatar_letters, letters)
                setColorStateList(R.id.avatar_letters, "setTextColor", on)
                // As the app's avatar: two letters a little smaller, so the wider pair keeps clear of the circle's edge
                setTextViewTextSize(R.id.avatar_letters, TypedValue.COMPLEX_UNIT_DIP, fit.avatar * if (letters.length > 1) 0.36f else 0.42f)
            }
            setViewVisibility(R.id.label, if (fit.labels) View.VISIBLE else View.GONE)
            setTextViewText(R.id.label, contact.shortName ?: contact.name)
            setContentDescription(R.id.cell, contact.name)
            setOnClickPendingIntent(R.id.cell, open(context, WidgetRequest.Contact(contact.lookupKey, contact.id)))
        }

    /** The letter avatar's fill and letters, by resource, as the app's: the palette by the lookup key, or one of the three theme container pairs. */
    private fun avatarColors(lookupKey: String, many: Boolean): Pair<Int, Int> = if (many) {
        AVATAR_PALETTE[Avatars.color(lookupKey)]
    } else {
        when (Avatars.tone(lookupKey)) {
            0 -> R.color.widget_primary_container to R.color.widget_on_primary_container
            1 -> R.color.widget_secondary_container to R.color.widget_on_secondary_container
            else -> R.color.widget_tertiary_container to R.color.widget_on_tertiary_container
        }
    }

    /** No access or no favorites (§14.3), as [FavoritesGrid.stateLook] says: the icon alone at 1 × 1, the sentence alone in one row; the whole card opens the app. */
    private fun state(context: Context, fit: FavoritesGrid.Fit, access: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_favorites_state).apply {
            val look = FavoritesGrid.stateLook(fit)
            val text = context.getString(if (access) R.string.msg_widget_no_favorites else R.string.msg_widget_no_permission)
            setImageViewResource(R.id.state_icon, if (access) R.drawable.ic_star else R.drawable.ic_person_off)
            setViewVisibility(R.id.state_icon, if (look == FavoritesGrid.StateLook.TEXT) View.GONE else View.VISIBLE)
            setTextViewText(R.id.state_text, text)
            setViewVisibility(R.id.state_text, if (look == FavoritesGrid.StateLook.ICON) View.GONE else View.VISIBLE)
            if (look == FavoritesGrid.StateLook.TEXT) {
                setViewLayoutMargin(R.id.state_text, RemoteViews.MARGIN_TOP, 0f, TypedValue.COMPLEX_UNIT_DIP)
                setInt(R.id.state_text, "setMaxLines", 2)
            }
            setTextViewText(R.id.state_button, context.getString(R.string.button_open_contacts))
            setViewVisibility(R.id.state_button, if (!access && look == FavoritesGrid.StateLook.FULL) View.VISIBLE else View.GONE)
            setContentDescription(android.R.id.background, text)
            val open = open(context, WidgetRequest.Open)
            setOnClickPendingIntent(android.R.id.background, open)
            setOnClickPendingIntent(R.id.state_button, open)
        }

    /** A contact's thumbnail cut to a circle of [px]; null when it is gone meanwhile or can't be read (the letter avatar stands in). */
    private fun photo(context: Context, address: String, px: Int): Bitmap? = try {
        // The fragment only tells one photo from the next (ContactSource.photoAddress); the storage gets the address
        context.contentResolver.loadThumbnail(address.substringBefore('#').toUri(), Size(px, px), null).round(px)
    } catch (_: Exception) {
        null
    }

    /** The middle square of the image, scaled to [px] and cut to a circle: a remote view can't clip an image itself. */
    private fun Bitmap.round(px: Int): Bitmap {
        val scale = px.toFloat() / minOf(width, height)
        val shader = BitmapShader(this, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((px - width * scale) / 2f, (px - height * scale) / 2f)
            })
        }
        val result = createBitmap(px, px)
        Canvas(result).drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        return result
    }

    /** Opens the app on what a tap asks for; the address tells the intents apart. */
    private fun open(context: Context, request: WidgetRequest): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, WidgetRequest.address(request), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Favorites, 4 × 2 by default, from 1 × 1 (SPEC §14.1). */
class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** Resized: as many favorites as the new size fits. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    /** The last one removed: the redraw finds no widget and stops watching the contacts. */
    override fun onDisabled(context: Context) = redraw(context)

    private fun redraw(context: Context) {
        val pending = goAsync()
        Widgets.update(context) { pending.finish() }
    }
}

/**
 * Redraws the widgets after a change of the contacts while the app is closed – a sync by the account's app above all (as Calendar's). The system runs the job when the storage's content changes; such a job runs once, so it is set again each time, while widgets are placed.
 */
class ChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as ContactsApplication
        app.appScope.launch {
            try {
                Widgets.updateAndWait(app)
            } finally {
                // Set again before this run is given back, as Calendar's: a job that is still running counts as waiting, so [ensure] would leave it be and the next change would wake nobody
                if (Widgets.placed(app) && app.contacts.hasAccess()) schedule(app)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val ID = 1

        /** Sets the job unless it is waiting already. */
        fun ensure(context: Context) {
            if (context.getSystemService(JobScheduler::class.java).getPendingJob(ID) == null) schedule(context)
        }

        private fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(ID, ComponentName(context, ChangeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(ContactsContract.AUTHORITY_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                // A sync writes many rows: wait for it to settle, but not longer than ten seconds
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(10_000)
                .build()
            try {
                scheduler.schedule(job)
            } catch (_: Exception) {
                // Refused (access taken away): the widgets follow at the app's next start
            }
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
