@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalConfiguration
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
import java.text.NumberFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.about.WhatsNew
import sh.hnet.contacts.data.Deletion
import sh.hnet.contacts.data.ImportJob
import sh.hnet.contacts.data.Moved
import sh.hnet.contacts.ui.accounts.AccountsScreen
import sh.hnet.contacts.ui.components.ContactsAccess
import sh.hnet.contacts.ui.components.ErrorDetails
import sh.hnet.contacts.ui.components.ErrorDetailsDialog
import sh.hnet.contacts.ui.components.reportFailure
import sh.hnet.contacts.ui.contact.ContactScreen
import sh.hnet.contacts.ui.contacts.ContactsScreen
import sh.hnet.contacts.ui.duplicates.DuplicatesScreen
import sh.hnet.contacts.ui.duplicates.JoinPickerScreen
import sh.hnet.contacts.ui.duplicates.ReviewScreen
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
import sh.hnet.contacts.ui.transfer.ExportScreen
import sh.hnet.contacts.ui.transfer.ImportScreen
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
            if ((asked is Screen.Editor && asked.external) || (asked is Screen.Import && asked.external)) while (stack.size > 1) stack.removeAt(stack.lastIndex)
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

    val movedOne = stringResource(R.string.msg_contact_moved)
    val movedMany = stringResource(R.string.msg_contacts_moved)
    val moveFailed = stringResource(R.string.error_move_failed)
    /**
     * A move done as far as copying (SPEC §8.4): the snackbar with Undo; the old entries go when it
     * has gone, Undo deletes the copies. [name] for one contact, null for several.
     */
    val movedWithUndo: (Moved, String, String?) -> Unit = { moved, target, name ->
        if (moved.newRawContactIds.isEmpty()) {
            messages.launch { snackbars.showSnackbar(moveFailed.format(0), duration = SnackbarDuration.Short) }
        } else {
            val message = when {
                moved.failed > 0 -> moveFailed.format(moved.newRawContactIds.size)
                name != null -> movedOne.format(name, target)
                else -> movedMany.format(moved.newRawContactIds.size, target)
            }
            deleteWithUndo(Deletion(emptySet(), moved.oldRawContactIds, 0, moved.newRawContactIds), message)
        }
    }
    val accountHidden = stringResource(R.string.msg_account_hidden)
    val thisPhone = stringResource(R.string.label_this_phone)
    val show = stringResource(R.string.button_show)

    val mergedText = stringResource(R.string.msg_merged_permanent)
    val joinedText = stringResource(R.string.msg_merged)
    val apartText = stringResource(R.string.msg_not_suggested)
    val joinedAllText = stringResource(R.string.msg_duplicates_merged)
    val joinFailed = stringResource(R.string.error_join_failed)
    /** A join or "not the same person" with Undo, which puts the exceptions back as they were. */
    val exceptionsWithUndo: (Map<Pair<Long, Long>, Int>, String) -> Unit = { before, message ->
        messages.launch {
            snackbars.currentSnackbarData?.dismiss()
            if (snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) app.writer.restore(before)
        }
    }

    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val open: (Screen) -> Unit = { stack.add(it) }

    // vCard import (SPEC §10.1): the system picker; another file picked on the import page replaces it
    val pickVcard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (stack.lastOrNull() is Screen.Import) stack.removeAt(stack.lastIndex)
        open(Screen.Import(listOf(uri.toString())))
    }
    val chooseVcard: () -> Unit = { pickVcard.launch(VCARD_TYPES) }

    // The import's outcome (SPEC §10.5, §10.6), wherever the user is when it ends
    val importedText = stringResource(R.string.msg_imported)
    val importedSkippedText = stringResource(R.string.msg_imported_skipped)
    val importFailedText = stringResource(R.string.error_import_failed)
    val undoImport = stringResource(R.string.button_undo_import)
    var importError by remember { mutableStateOf<Pair<ErrorDetails, List<Long>>?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(Unit) {
        app.imports.state.collect { state ->
            if (state !is ImportJob.State.Finished) return@collect
            app.imports.clear()
            val outcome = state.outcome
            stack.removeAll { it is Screen.Import }
            val format = NumberFormat.getIntegerInstance(locale)
            val count = format.format(outcome.rawContactIds.size)
            when {
                outcome.error != null -> messages.reportFailure(snackbars, importFailedText.format(count), outcome.error.toString(), show) { importError = it to outcome.rawContactIds }
                outcome.opened != null -> open(Screen.Contact(outcome.opened.lookupKey, outcome.opened.contactId))
                else -> messages.launch {
                    snackbars.currentSnackbarData?.dismiss()
                    val message = if (outcome.skipped > 0) importedSkippedText.format(count, outcome.accountLabel, format.format(outcome.skipped)) else importedText.format(count, outcome.accountLabel)
                    // Undo takes away exactly what this import wrote
                    if (snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        runCatching { app.writer.delete(outcome.rawContactIds) }
                    }
                }
            }
        }
    }

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
                            onImport = chooseVcard,
                            onOpenAccounts = { open(Screen.Accounts) },
                            onOpenDuplicates = { open(Screen.Duplicates) },
                            onMerge = { ids -> open(Screen.Review(ids, suggested = false)) },
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
                                    // Saved into an account the list hides (SPEC §4.8): say so, with Show
                                    saved.account?.let { account ->
                                        messages.launch {
                                            if (account.key !in app.preferences.hiddenAccounts.first()) return@launch
                                            val label = account.name.ifEmpty { thisPhone }
                                            if (snackbars.showSnackbar(accountHidden.format(label), actionLabel = show, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                                app.preferences.setAccountShown(account.key, true)
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                    entry<Screen.Contact> { contact ->
                        ContactScreen(
                            contact.lookupKey, contact.id, contact.name, contact.photo, snackbars, messages,
                            onBack = back,
                            onEdit = { id, key -> open(Screen.Editor(key, id, System.nanoTime())) },
                            onMoved = movedWithUndo,
                            onJoin = { id, name -> open(Screen.JoinPicker(id, name)) },
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
                            onOpenAccounts = { open(Screen.Accounts) },
                            onOpenDuplicates = { open(Screen.Duplicates) },
                            onOpenLanguage = { open(Screen.SettingsLanguage) },
                            onOpenImport = chooseVcard,
                            onOpenExport = { open(Screen.Export) },
                            onOpenBackup = { open(Screen.SettingsBackup) },
                            onOpenReset = { open(Screen.SettingsReset) },
                            onOpenAbout = { open(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsList> { ListSettingsScreen(onBack = back) }
                    entry<Screen.SettingsContacts> { ContactsSettingsScreen(onBack = back) }
                    entry<Screen.Duplicates> {
                        DuplicatesScreen(
                            onBack = back,
                            onReview = { open(Screen.Review(it.ids, suggested = true)) },
                            onJoinAll = { groups ->
                                messages.launch {
                                    val before = app.writer.join(groups.map { it.ids })
                                    exceptionsWithUndo(before, joinedAllText.format(groups.size))
                                }
                            },
                        )
                    }
                    entry<Screen.Review> { review ->
                        ReviewScreen(
                            review.ids, review.suggested,
                            onBack = back,
                            onMerged = { _, name ->
                                back()
                                messages.launch {
                                    snackbars.currentSnackbarData?.dismiss()
                                    snackbars.showSnackbar(mergedText.format(name), duration = SnackbarDuration.Short)
                                }
                            },
                            onJoined = { before, name ->
                                back()
                                exceptionsWithUndo(before, joinedText.format(name))
                            },
                            onApart = { before ->
                                back()
                                exceptionsWithUndo(before, apartText)
                            },
                            onFailed = { messages.launch { snackbars.showSnackbar(joinFailed, duration = SnackbarDuration.Short) } },
                        )
                    }
                    entry<Screen.JoinPicker> { picker ->
                        JoinPickerScreen(picker.id, picker.name, onBack = back, onPick = { other ->
                            back()
                            open(Screen.Review(listOf(picker.id, other), suggested = false))
                        })
                    }
                    entry<Screen.Accounts> { AccountsScreen(onBack = back, onMoved = { moved, target -> movedWithUndo(moved, target, null) }, onExport = { open(Screen.Export) }) }
                    entry<Screen.Import> { import ->
                        ImportScreen(
                            import.uris,
                            // Opened from another app: Close goes back to it
                            onClose = { if (import.external) activity?.finish() else back() },
                            onChooseOther = if (import.external) null else chooseVcard,
                        )
                    }
                    entry<Screen.Export> { ExportScreen(onBack = back, snackbars = snackbars, messageScope = messages) }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsBackup> { BackupScreen(onBack = back, snackbars = snackbars, messageScope = messages, onExport = { open(Screen.Export) }) }
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
    // A failed import's details, with Undo for what it wrote before failing (owner's decision of 18 Nov 2026)
    importError?.let { (error, written) ->
        ErrorDetailsDialog(error, extra = if (written.isEmpty()) null else undoImport to { messages.launch { runCatching { app.writer.delete(written) } } }) { importError = null }
    }
}

/** What the picker offers for an import (SPEC §10.1); some file apps call a .vcf file only "binary". */
private val VCARD_TYPES = arrayOf("text/vcard", "text/x-vcard", "text/directory", "application/octet-stream")
