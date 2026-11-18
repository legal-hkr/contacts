@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.contacts.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ContactWidgetPlan.SmallButton
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.model.Search
import sh.hnet.contacts.model.SearchContact
import sh.hnet.contacts.model.SearchResult
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.FormPage
import sh.hnet.contacts.ui.components.LocalAvatarColors
import sh.hnet.contacts.ui.components.SettingsGroup
import sh.hnet.contacts.ui.components.SettingsNavigationRow
import sh.hnet.contacts.ui.components.SettingsRadioRow
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.contacts.NoAccess
import sh.hnet.contacts.ui.contacts.SearchField
import sh.hnet.contacts.ui.theme.ContactsTheme

/**
 * The contact each one-contact widget shows and its button at 1 × 1 (SPEC §14.2, the owner's D1 of 18 Nov 2026), by widget id, in a store of its own as News's widget sources: not a setting, so Reset and the backup leave it alone, and gone with its widget.
 */
object ContactWidgets {
    data class Chosen(val lookupKey: String, val contactId: Long, val button: SmallButton)

    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(context: Context, id: Int): Chosen? {
        val prefs = prefs(context)
        val contact = prefs.getString("$CONTACT$id", null) ?: return null
        val contactId = contact.substringBefore('\n').toLongOrNull() ?: return null
        return Chosen(contact.substringAfter('\n'), contactId, SmallButton.of(prefs.getString("$BUTTON$id", null)))
    }

    @Synchronized
    fun set(context: Context, id: Int, lookupKey: String, contactId: Long, button: SmallButton) = prefs(context).edit {
        putString("$CONTACT$id", "$contactId\n$lookupKey")
        putString("$BUTTON$id", button.key)
    }

    /** The contact got another key or id (a join, a sync): the widget follows it – only while it still shows [from], read before the redraw; a contact chosen meanwhile, or the widget removed, wins (the code review of 18 Nov 2026). */
    @Synchronized
    fun follow(context: Context, id: Int, from: Chosen, lookupKey: String, contactId: Long) {
        val prefs = prefs(context)
        if (prefs.getString("$CONTACT$id", null) != "${from.contactId}\n${from.lookupKey}") return
        prefs.edit { putString("$CONTACT$id", "$contactId\n$lookupKey") }
    }

    @Synchronized
    fun forget(context: Context, ids: IntArray) = prefs(context).edit {
        ids.forEach {
            remove("$CONTACT$it")
            remove("$BUTTON$it")
        }
    }

    private const val CONTACT = "contact:"
    private const val BUTTON = "button:"
}

/**
 * Setting up a one-contact widget (SPEC §14.2, the owner's D1 and D4 of 18 Nov 2026), in two steps: "Choose a contact" – search and the list as joining's (§9.6), shown accounts only –, then the widget's page with the button at 1 × 1 (Call, Message or none), "Change contact" and Done, which adds the widget. Opened by the launcher when the widget is placed (Close or Back leave without adding it) and again from its edit, then at the second step; the widget's "Choose another" opens the first.
 */
class ContactWidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val chosen = ContactWidgets.get(this, id)
        val choosing = chosen == null || intent.getBooleanExtra(EXTRA_CHOOSE, false)
        setContent {
            ContactsTheme {
                Setup(chosen, choosing, onClose = ::finish) { lookupKey, contactId, button ->
                    ContactWidgets.set(this, id, lookupKey, contactId, button)
                    Widgets.update(this)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }

    companion object {
        /** Opens at the contact's choice even when the widget has one ("Choose another"). */
        const val EXTRA_CHOOSE = "sh.hnet.contacts.extra.CHOOSE"
    }
}

@Composable
private fun Setup(chosen: ContactWidgets.Chosen?, choosing: Boolean, onClose: () -> Unit, onDone: (String, Long, SmallButton) -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val settings by app.preferences.listSettings.collectAsStateWithLifecycle(ListSettings())
    var lookupKey by rememberSaveable { mutableStateOf(chosen?.lookupKey) }
    var contactId by rememberSaveable { mutableLongStateOf(chosen?.contactId ?: 0L) }
    var button by rememberSaveable { mutableStateOf((chosen?.button ?: SmallButton.CALL).key) }
    var picking by rememberSaveable { mutableStateOf(choosing) }
    // "Change contact" on the second step: Back from the choice returns there instead of leaving
    var fromPage by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalAvatarColors provides settings.avatarColors) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            if (picking) {
                val back = { if (fromPage) picking = false else onClose() }
                BackHandler(onBack = back)
                Picker(onBack = back) { key, id ->
                    lookupKey = key
                    contactId = id
                    picking = false
                }
            } else {
                val key = lookupKey ?: return@Surface
                val name by produceState<String?>(null, key, contactId) {
                    value = runCatching { app.contacts.details(key, contactId, settings.lastFirst)?.name }.getOrNull()
                }
                FormPage(
                    title = name ?: stringResource(R.string.title_widget_contact),
                    confirmLabel = stringResource(R.string.button_done),
                    onConfirm = { onDone(key, contactId, SmallButton.of(button)) },
                    onBack = onClose,
                    navigationIcon = R.drawable.ic_close,
                    navigationLabel = stringResource(R.string.button_close),
                    contentSpacing = 16.dp,
                ) {
                    Column(Modifier.selectableGroup()) {
                        SettingsGroup(stringResource(R.string.label_widget_small_button)) {
                            listOf(SmallButton.CALL to R.string.tooltip_call, SmallButton.MESSAGE to R.string.tooltip_message, SmallButton.NONE to R.string.label_no_button).forEach { (choice, label) ->
                                row { shapes -> SettingsRadioRow(shapes, stringResource(label), button == choice.key, onSelect = { button = choice.key }) }
                            }
                        }
                    }
                    SettingsGroup {
                        row { shapes ->
                            SettingsNavigationRow(shapes, R.drawable.ic_account_circle, stringResource(R.string.label_change_contact)) {
                                fromPage = true
                                picking = true
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Choose a contact": the search field focused, the title under it, the contacts of shown accounts; without access, the list's own no-access state. */
@Composable
private fun Picker(onBack: () -> Unit, onPick: (String, Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    var access by remember { mutableStateOf(app.contacts.hasAccess()) }
    LifecycleResumeEffect(Unit) {
        access = app.contacts.hasAccess()
        onPauseOrDispose {}
    }
    if (!access) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.title_choose_contact)) },
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            )
            NoAccess(onAnswer = { access = app.contacts.hasAccess() })
        }
        return
    }
    val field = rememberTextFieldState()
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(emptySet())
    val index by produceState<List<SearchContact>>(emptyList(), hidden) { value = runCatching { app.contacts.searchIndex(ListSettings(), hidden) }.getOrDefault(emptyList()).filter { it.shown } }
    val results by produceState<List<SearchResult>>(emptyList(), index) {
        snapshotFlow { field.text.toString() }.collectLatest { text ->
            value = if (text.isBlank()) index.sortedBy { it.row.sortName.lowercase() }.map { SearchResult(it.row, true, null, it.row.name, null) }
            else Search.find(index, text, app.contacts.countryCode(), all = false)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        SearchField(field, field.text.toString(), onClose = onBack, onClear = { field.clearText() })
        Text(stringResource(R.string.title_choose_contact), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            items(results, key = { it.contact.id }) { result ->
                ListItem(onClick = { onPick(result.contact.lookupKey, result.contact.id) }, leadingContent = { Avatar(result.contact.name, result.contact.lookupKey, result.contact.photo, 40.dp, initials = result.contact.initials) }) {
                    Text(result.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
