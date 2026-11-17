@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.about.WhatsNew
import sh.hnet.contacts.data.Deletion
import sh.hnet.contacts.ui.components.ContactsAccess
import sh.hnet.contacts.ui.contact.ContactScreen
import sh.hnet.contacts.ui.contacts.ContactsScreen
import sh.hnet.contacts.ui.editor.EditorScreen
import sh.hnet.contacts.ui.editor.Prefill
import sh.hnet.contacts.ui.settings.AboutScreen
import sh.hnet.contacts.ui.settings.AppLicenseScreen
import sh.hnet.contacts.ui.settings.BackupScreen
import sh.hnet.contacts.ui.settings.ContactsSettingsScreen
import sh.hnet.contacts.ui.settings.LanguageScreen
import sh.hnet.contacts.ui.settings.LicensesScreen
import sh.hnet.contacts.ui.settings.ListSettingsScreen
import sh.hnet.contacts.ui.settings.ResetScreen
import sh.hnet.contacts.ui.settings.SettingsRootScreen
import sh.hnet.contacts.ui.settings.WhatsNewScreen
import sh.hnet.contacts.ui.welcome.WizardScreen

/**
 * The app's shell: one back stack with the contact list at its root; Settings and everything else
 * open over it as pages, with Back. On the first start the wizard lies over it all (SPEC §12).
 */
@Composable
fun ContactsApp(asked: Screen? = null, onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val stack = rememberNavBackStack(Screen.Contacts)
    val snackbars = remember { SnackbarHostState() }

    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair, Music, Clock and News)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = context.applicationContext
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(Screen.Settings)
            stack.add(Screen.SettingsAbout)
            stack.add(Screen.SettingsWhatsNew)
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    val messages = rememberCoroutineScope()
    var wizard by rememberSaveable { mutableStateOf(!ContactsAccess.wizardDone(appContext)) }

    // What another app asked for: its page over the list, whatever was open
    LaunchedEffect(asked) {
        if (asked != null) {
            // Started for another app's result: its editor alone over the list; reached while running: over what is open, which stays
            if (asked is Screen.Editor && asked.external) while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(asked)
            onOpened()
        }
    }

    val app = appContext as ContactsApplication
    val undo = stringResource(R.string.button_undo)
    // Contacts leave every view at once; the storage hears of it when the snackbar has gone (SPEC §6.8)
    val deleteWithUndo: (Deletion, String) -> Unit = { deletion, message ->
        app.deletes.delete(deletion)
        messages.launch {
            snackbars.currentSnackbarData?.dismiss()
            // Carried out elsewhere meanwhile (the app left the screen, another delete): Undo has nothing left to offer
            val watch = launch {
                app.deletes.waiting.first { it != deletion }
                snackbars.currentSnackbarData?.dismiss()
            }
            val result = snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short)
            watch.cancel()
            if (app.deletes.waiting.value == deletion) {
                if (result == SnackbarResult.ActionPerformed) app.deletes.undo() else app.deletes.commit()
            }
        }
    }

    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val open: (Screen) -> Unit = { stack.add(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                onBack = back,
                // Every page's view models belong to its entry and go with it: a new contact's editor starts empty, an editor
                // opened again reads the contact again, and pages left behind stop watching the storage
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                // Shared axis X between the contact list and the pages over it
                transitionSpec = {
                    (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
                },
                popTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                entryProvider = entryProvider<NavKey> {
                    entry<Screen.Contacts> {
                        ContactsScreen(
                            onOpenSettings = { open(Screen.Settings) },
                            onOpenContact = { open(Screen.Contact(it.lookupKey, it.id, it.name, it.photo)) },
                            onNewContact = { open(Screen.Editor(null, 0, System.nanoTime())) },
                            onDelete = deleteWithUndo,
                            snackbars = snackbars,
                            messageScope = messages,
                        )
                    }
                    entry<Screen.Editor> { editor ->
                        EditorScreen(
                            editor.lookupKey, editor.id, editor.session, Prefill(editor.name, editor.phone, editor.email), snackbars, messages,
                            onClose = {
                                // Another app's "Create contact": Close goes back to it, without a contact
                                if (editor.external) activity?.run { setResult(Activity.RESULT_CANCELED); finish() } else back()
                            },
                            onSaved = { saved ->
                                if (editor.external) {
                                    activity?.run {
                                        setResult(Activity.RESULT_OK, Intent().setData(ContactsContract.Contacts.getLookupUri(saved.contactId, saved.lookupKey)))
                                        finish()
                                    }
                                } else {
                                    back()
                                    // A new contact's page opens; an edited one's page was under the editor already
                                    if (editor.lookupKey == null) open(Screen.Contact(saved.lookupKey, saved.contactId))
                                }
                            },
                        )
                    }
                    entry<Screen.Contact> { contact ->
                        ContactScreen(
                            contact.lookupKey, contact.id, contact.name, contact.photo, snackbars, messages,
                            onBack = back,
                            onEdit = { id, key -> open(Screen.Editor(key, id, System.nanoTime())) },
                            onDelete = { deletion, message ->
                                // The page closes, back to where it came from, and the contact leaves the list at once
                                back()
                                deleteWithUndo(deletion, message)
                            },
                            onOpenContact = { id, key -> open(Screen.Contact(key, id)) },
                            onSearch = { name ->
                                app.pendingSearch.value = name
                                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                            },
                        )
                    }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onBack = back,
                            onOpenList = { open(Screen.SettingsList) },
                            onOpenContacts = { open(Screen.SettingsContacts) },
                            onOpenLanguage = { open(Screen.SettingsLanguage) },
                            onOpenBackup = { open(Screen.SettingsBackup) },
                            onOpenReset = { open(Screen.SettingsReset) },
                            onOpenAbout = { open(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsList> { ListSettingsScreen(onBack = back) }
                    entry<Screen.SettingsContacts> { ContactsSettingsScreen(onBack = back) }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsBackup> { BackupScreen(onBack = back, snackbars = snackbars, messageScope = messages) }
                    entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(
                            onBack = back,
                            onOpenWhatsNew = { open(Screen.SettingsWhatsNew) },
                            onOpenLicense = { open(Screen.SettingsLicense) },
                            onOpenLicenses = { open(Screen.SettingsLicenses) },
                        )
                    }
                    entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                    entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
                    entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                },
            )
            // The first run (SPEC §12): the wizard lies over the list until it is done
            if (wizard) {
                WizardScreen(snackbars, messages, onDone = {
                    ContactsAccess.markWizardDone(appContext)
                    wizard = false
                })
            }
            // One host for every page, above the system's navigation area
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}
