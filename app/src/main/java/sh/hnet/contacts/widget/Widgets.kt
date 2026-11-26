package sh.hnet.contacts.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import sh.hnet.contacts.model.ContactDetails
import sh.hnet.contacts.model.ContactList
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.ContactWidgetPlan
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.FavoritesGrid
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.model.favorites
import sh.hnet.contacts.ui.components.AVATAR_PALETTE
import sh.hnet.contacts.ui.components.THUMBNAIL_PX

/** What a tap on a widget asks the app for, carried as the intent's address so that each tap's intent is its own. */
sealed interface WidgetRequest {
    /** A contact's page, by its lookup key, which survives joins and syncs; [ask] opens the number sheet for a call or a message the one-contact widget couldn't make alone ([ASK_CALL], [ASK_MESSAGE]). */
    data class Contact(val lookupKey: String, val id: Long, val ask: String? = null) : WidgetRequest

    /** The list: it shows its own state when access is missing. */
    data object Open : WidgetRequest

    companion object {
        const val SCHEME = "contacts-widget"
        const val ASK_CALL = "call"
        const val ASK_MESSAGE = "message"

        fun of(address: Uri?): WidgetRequest? {
            if (address?.scheme != SCHEME) return null
            return when (address.host) {
                "contact" -> address.getQueryParameter("lookup")?.takeIf { it.isNotEmpty() }?.let {
                    Contact(it, address.getQueryParameter("id")?.toLongOrNull() ?: 0, address.getQueryParameter("ask")?.takeIf { ask -> ask == ASK_CALL || ask == ASK_MESSAGE })
                }
                "open" -> Open
                else -> null
            }
        }

        fun address(request: WidgetRequest): Uri = Uri.Builder().scheme(SCHEME).apply {
            when (request) {
                is Contact -> {
                    authority("contact").appendQueryParameter("lookup", request.lookupKey).appendQueryParameter("id", request.id.toString())
                    request.ask?.let { appendQueryParameter("ask", it) }
                }
                Open -> authority("open")
            }
        }.build()
    }
}

/**
 * The home-screen widgets (SPEC §14; stage S9: Favorites, S10: one contact), plain remote views as Calendar's, floating on the wallpaper as its Agenda (the owner, 18 Nov 2026). Favorites are the list's favorites row: the starred contacts of shown accounts in the list's order, under the same short names, with the same avatars – the letter avatars' colors follow Settings › List. The one-contact widget shows the contact chosen when it was placed ([ContactWidgets]), with Call and Message. Colors are resources the launcher resolves, so a change of theme needs no redraw. Texts are set here: a layout's own strings would come out in the launcher's language rather than the app's.
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
    fun placed(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return PROVIDERS.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    private val PROVIDERS = listOf(FavoritesWidget::class.java, ContactWidget::class.java)

    private suspend fun draw(app: ContactsApplication) {
        val manager = AppWidgetManager.getInstance(app)
        val favoriteIds = manager.getAppWidgetIds(ComponentName(app, FavoritesWidget::class.java))
        val contactIds = manager.getAppWidgetIds(ComponentName(app, ContactWidget::class.java))
        if (favoriteIds.isEmpty() && contactIds.isEmpty()) {
            ChangeJob.cancel(app)
            return
        }
        val access = app.contacts.hasAccess()
        if (access) ChangeJob.ensure(app)
        val settings = app.preferences.listSettings.first()
        val locale = app.resources.configuration.locales[0]
        if (favoriteIds.isNotEmpty()) drawFavorites(app, manager, favoriteIds, access, settings, locale)
        if (contactIds.isNotEmpty()) drawContacts(app, manager, contactIds, access, settings, locale)
    }

    private suspend fun drawFavorites(app: ContactsApplication, manager: AppWidgetManager, ids: IntArray, access: Boolean, settings: ListSettings, locale: Locale) {
        val favorites = if (access) ContactList.favorites(app.contacts.contacts(settings, app.preferences.hiddenAccounts.first()).contacts, locale) else emptyList()

        val fits = ids.associateWith { manager.getAppWidgetOptions(it).let { options -> FavoritesGrid.fit(options.width(250), options.height(180)) } }
        // Each photo read once, at the size the largest avatar of any widget needs
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (fits.values.maxOfOrNull { it.avatar } ?: FavoritesGrid.AVATAR).toFloat(), app.resources.displayMetrics).toInt()
        val wanted = favorites.take(fits.values.maxOfOrNull { it.count } ?: 0)
        // Larger than the storage's thumbnail: from the full photo, as the app's avatars (the thumbnail would be blurred and blocky); kept under the thumbnail's address, which the cells look up
        val photos = withContext(Dispatchers.IO) { wanted.mapNotNull { row -> row.photo?.let { address -> photo(app, if (px > THUMBNAIL_PX) row.largePhoto ?: address else address, px)?.let { address to it } } }.toMap() }

        ids.forEach { id ->
            val fit = fits.getValue(id)
            val views = when {
                !access -> state(app, FavoritesGrid.stateLook(fit), R.drawable.ic_person_off, R.string.msg_widget_no_permission, R.string.button_open_contacts, open(app, WidgetRequest.Open))
                favorites.isEmpty() -> state(app, FavoritesGrid.stateLook(fit), R.drawable.ic_star, R.string.msg_widget_no_favorites, null, open(app, WidgetRequest.Open))
                else -> grid(app, fit, favorites.take(fit.count), photos, settings.avatarColors, locale)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /**
     * The one-contact widgets (§14.2, §14.3): the contact found again by its lookup key – the key kept up to date after joins –, or its state. A widget not set up yet keeps its empty circle.
     */
    private suspend fun drawContacts(app: ContactsApplication, manager: AppWidgetManager, ids: IntArray, access: Boolean, settings: ListSettings, locale: Locale) {
        val hidden = app.preferences.hiddenAccounts.first()
        ids.forEach { id ->
            val chosen = ContactWidgets.get(app, id)
            val layout = manager.getAppWidgetOptions(id).let { options -> ContactWidgetPlan.layout(options.width(130), options.height(130)) }
            val look = when (layout.look) {
                ContactWidgetPlan.Look.SQUARE -> FavoritesGrid.StateLook.FULL
                ContactWidgetPlan.Look.WIDE -> FavoritesGrid.StateLook.TEXT
                ContactWidgetPlan.Look.SMALL -> FavoritesGrid.StateLook.ICON
            }
            val views = when {
                chosen == null -> RemoteViews(app.packageName, R.layout.widget_contact_loading)
                !access -> state(app, look, R.drawable.ic_person_off, R.string.msg_widget_no_permission, R.string.button_open_contacts, open(app, WidgetRequest.Open))
                else -> {
                    val contact = try {
                        app.contacts.details(chosen.lookupKey, chosen.contactId, settings.lastFirst)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Not readable just now: the widget keeps what it shows
                        return@forEach
                    }
                    when {
                        contact == null -> state(app, look, R.drawable.ic_person_add, R.string.msg_widget_deleted, R.string.button_choose_another, choose(app, id))
                        contact.sources.isNotEmpty() && contact.sources.all { it.account.key in hidden } ->
                            state(app, look, R.drawable.ic_person_add, R.string.msg_widget_hidden, R.string.button_choose_another, choose(app, id))
                        else -> {
                            // A join or a sync gave the contact another key or id: the widget follows it
                            if (contact.lookupKey != chosen.lookupKey || contact.id != chosen.contactId) ContactWidgets.follow(app, id, chosen, contact.lookupKey, contact.id)
                            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, layout.circle.toFloat(), app.resources.displayMetrics).toInt()
                            val photo = if (contact.photo == null) null else withContext(Dispatchers.IO) { largePhoto(app, contact.id, px) }
                            oneContact(app, layout, contact, chosen.button, photo, settings.avatarColors, locale)
                        }
                    }
                }
            }
            manager.updateAppWidget(id, views)
        }
    }

    /** The one contact (§14.2) at its size: the round photo, at 2 × 2 the short name in a pill on its top end corner, and the buttons – both at 2 × 2 and 2 × 1, the one chosen at 1 × 1. */
    private fun oneContact(context: Context, layout: ContactWidgetPlan.Layout, contact: ContactDetails, button: ContactWidgetPlan.SmallButton, photo: Bitmap?, manyColors: Boolean, locale: Locale): RemoteViews {
        val res = when (layout.look) {
            ContactWidgetPlan.Look.SQUARE -> R.layout.widget_contact_square
            ContactWidgetPlan.Look.WIDE -> R.layout.widget_contact_wide
            ContactWidgetPlan.Look.SMALL -> R.layout.widget_contact_small
        }
        return RemoteViews(context.packageName, res).apply {
            setViewLayoutWidth(R.id.disc, layout.circle.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.disc, layout.circle.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            avatar(contact.lookupKey, contact.name, contact.initials, photo, layout.circle, manyColors, locale)
            setContentDescription(R.id.avatar, contact.name)
            setOnClickPendingIntent(R.id.avatar, open(context, WidgetRequest.Contact(contact.lookupKey, contact.id)))
            if (layout.look == ContactWidgetPlan.Look.SQUARE) setTextViewText(R.id.name, contact.nickname ?: contact.givenName ?: contact.name)

            val number = ContactWidgetPlan.number(contact.details[DetailKind.PHONE].orEmpty().map { it.value to it.isDefault })
            fun action(ask: String): PendingIntent? = when (number) {
                is ContactWidgetPlan.Number.Direct -> other(context, if (ask == WidgetRequest.ASK_CALL) Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.value, null)) else Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number.value, null)))
                ContactWidgetPlan.Number.Ask -> open(context, WidgetRequest.Contact(contact.lookupKey, contact.id, ask))
                ContactWidgetPlan.Number.None -> null
            }
            val call = action(WidgetRequest.ASK_CALL)
            val message = action(WidgetRequest.ASK_MESSAGE)
            val shown = when {
                call == null -> emptySet()
                layout.look != ContactWidgetPlan.Look.SMALL -> setOf(R.id.call, R.id.message)
                button == ContactWidgetPlan.SmallButton.CALL -> setOf(R.id.call)
                button == ContactWidgetPlan.SmallButton.MESSAGE -> setOf(R.id.message)
                else -> emptySet()
            }
            for ((view, intent, label) in listOf(Triple(R.id.call, call, R.string.tooltip_call), Triple(R.id.message, message, R.string.tooltip_message))) {
                setViewVisibility(view, if (view in shown) View.VISIBLE else View.GONE)
                if (view !in shown || intent == null) continue
                setOnClickPendingIntent(view, intent)
                setContentDescription(view, context.getString(label))
                setViewLayoutWidth(view, layout.button.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(view, layout.button.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                // The icon at half the button, as at 48 dp
                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, layout.button / 4f, context.resources.displayMetrics).toInt()
                setViewPadding(view, padding, padding, padding, padding)
            }
            if (layout.look == ContactWidgetPlan.Look.WIDE) {
                // Over the circle's end edge by half a button
                setViewLayoutMargin(R.id.buttons, RemoteViews.MARGIN_START, -layout.button / 2f, TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutMargin(R.id.message, RemoteViews.MARGIN_TOP, ContactWidgetPlan.WIDE_GAP.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
            if (layout.look != ContactWidgetPlan.Look.SMALL) setViewVisibility(R.id.buttons, if (shown.isEmpty()) View.GONE else View.VISIBLE)
        }
    }

    /** The widget's width and height in dp as the launcher reports them in portrait, or [fallback] when it doesn't. */
    private fun Bundle.width(fallback: Int): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: fallback

    private fun Bundle.height(fallback: Int): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: fallback

    /** Favorites (§14.1): the header pill where it fits, then the rows; the last row's empty cells keep the columns in place. */
    private fun grid(context: Context, fit: FavoritesGrid.Fit, contacts: List<ContactRow>, photos: Map<String, Bitmap>, manyColors: Boolean, locale: Locale): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_favorites).apply {
            setViewVisibility(R.id.header, if (fit.title) View.VISIBLE else View.GONE)
            setTextViewText(R.id.badge_text, context.getString(R.string.title_widget_favorites))
            setOnClickPendingIntent(R.id.badge, open(context, WidgetRequest.Open))
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
            setViewVisibility(R.id.cell, if (contact == null) View.INVISIBLE else View.VISIBLE)
            if (contact == null) return@apply
            setViewLayoutWidth(R.id.avatar, fit.avatar.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.avatar, fit.avatar.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            avatar(contact.lookupKey, contact.name, contact.initials, contact.photo?.let { photos[it] }, fit.avatar, manyColors, locale)
            setViewVisibility(R.id.label, if (fit.labels) View.VISIBLE else View.GONE)
            setTextViewText(R.id.label, contact.shortName ?: contact.name)
            setContentDescription(R.id.cell, contact.name)
            setOnClickPendingIntent(R.id.cell, open(context, WidgetRequest.Contact(contact.lookupKey, contact.id)))
        }

    /** An avatar of [size] dp, as the app's: the photo already cut to a circle, or the letters (two, given and family name, else the name's first) on the letter avatar's colors. */
    private fun RemoteViews.avatar(lookupKey: String, name: String, initials: String?, photo: Bitmap?, size: Int, manyColors: Boolean, locale: Locale) {
        // Every view set both ways: the launcher applies a redraw onto the views it already has, so a photo left visible stayed over the next contact's letters (the owner, 18 Nov 2026)
        setViewVisibility(R.id.avatar_fill, if (photo == null) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.avatar_letters, if (photo == null) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.avatar_photo, if (photo == null) View.GONE else View.VISIBLE)
        if (photo != null) {
            setImageViewBitmap(R.id.avatar_photo, photo)
            return
        }
        val (fill, on) = avatarColors(lookupKey, manyColors)
        val letters = initials ?: Avatars.initial(name, locale)
        setColorStateList(R.id.avatar_fill, "setImageTintList", fill)
        setTextViewText(R.id.avatar_letters, letters)
        setColorStateList(R.id.avatar_letters, "setTextColor", on)
        // Two letters a little smaller, so the wider pair keeps clear of the circle's edge
        setTextViewTextSize(R.id.avatar_letters, TypedValue.COMPLEX_UNIT_DIP, size * if (letters.length > 1) 0.36f else 0.42f)
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

    /**
     * A state (§14.3) as [look] says: the icon alone in one column or at 1 × 1, the sentence alone one row high, else the icon, the sentence and the [button]; the whole piece does what the button does.
     */
    private fun state(context: Context, look: FavoritesGrid.StateLook, @DrawableRes icon: Int, @StringRes text: Int, @StringRes button: Int?, action: PendingIntent): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_state).apply {
            val sentence = context.getString(text)
            setImageViewResource(R.id.state_icon, icon)
            setViewVisibility(R.id.state_icon, if (look == FavoritesGrid.StateLook.TEXT) View.GONE else View.VISIBLE)
            setTextViewText(R.id.state_text, sentence)
            setViewVisibility(R.id.state_text, if (look == FavoritesGrid.StateLook.ICON) View.GONE else View.VISIBLE)
            setViewLayoutMargin(R.id.state_text, RemoteViews.MARGIN_TOP, if (look == FavoritesGrid.StateLook.TEXT) 0f else 8f, TypedValue.COMPLEX_UNIT_DIP)
            setInt(R.id.state_text, "setMaxLines", if (look == FavoritesGrid.StateLook.TEXT) 2 else 3)
            button?.let { setTextViewText(R.id.state_button, context.getString(it)) }
            setViewVisibility(R.id.state_button, if (button != null && look == FavoritesGrid.StateLook.FULL) View.VISIBLE else View.GONE)
            setContentDescription(R.id.state_piece, sentence)
            setOnClickPendingIntent(R.id.state_piece, action)
            setOnClickPendingIntent(R.id.state_button, action)
        }

    /** A contact's thumbnail cut to a circle of [px]; null when it is gone meanwhile or can't be read (the letter avatar stands in). */
    private fun photo(context: Context, address: String, px: Int): Bitmap? = try {
        // The fragment only tells one photo from the next (ContactSource.photoAddress); the storage gets the address
        context.contentResolver.loadThumbnail(address.substringBefore('#').toUri(), Size(px, px), null).round(px)
    } catch (_: Exception) {
        null
    }

    /** A contact's large photo (the display photo when there is one: the thumbnail would be blurred in a large circle) cut to a circle of [px]; null when it can't be read. */
    private fun largePhoto(context: Context, contactId: Long, px: Int): Bitmap? = try {
        ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), true)
            ?.use { BitmapFactory.decodeStream(it) }?.round(px)
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

    /** Another app's activity: the dialer or the messages, with the number. */
    private fun other(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, 0, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** "Choose another": the widget's setup at the contact's choice; the address tells the widgets apart. */
    private fun choose(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, ContactWidgetSetupActivity::class.java)
            .setData(Uri.Builder().scheme(WidgetRequest.SCHEME).authority("choose").appendPath(id.toString()).build())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(ContactWidgetSetupActivity.EXTRA_CHOOSE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Favorites, 4 × 2 by default, from 2 × 1 (SPEC §14.1). */
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

/** One contact, 2 × 2 by default, from 1 × 1 (SPEC §14.2); set up by [ContactWidgetSetupActivity]. */
class ContactWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** Resized: another look may fit. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    /** Removed: its choice goes with it. */
    override fun onDeleted(context: Context, ids: IntArray) = ContactWidgets.forget(context, ids)

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
