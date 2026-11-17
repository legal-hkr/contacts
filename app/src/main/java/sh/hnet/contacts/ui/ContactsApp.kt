@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui

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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.about.WhatsNew
import sh.hnet.contacts.ui.components.ContactsAccess
import sh.hnet.contacts.ui.contact.ContactScreen
import sh.hnet.contacts.ui.contacts.ContactsScreen
import sh.hnet.contacts.ui.settings.AboutScreen
import sh.hnet.contacts.ui.settings.AppLicenseScreen
import sh.hnet.contacts.ui.settings.BackupScreen
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
fun ContactsApp() {
    val context = LocalContext.current
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

    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val open: (Screen) -> Unit = { stack.add(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                onBack = back,
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
                    entry<Screen.Contacts> { ContactsScreen(onOpenSettings = { open(Screen.Settings) }, onOpenContact = { open(Screen.Contact(it.lookupKey, it.id)) }) }
                    entry<Screen.Contact> { contact ->
                        ContactScreen(
                            contact.lookupKey, contact.id, snackbars, messages,
                            onBack = back,
                            onOpenContact = { id, key -> open(Screen.Contact(key, id)) },
                            onSearch = { name ->
                                (appContext as ContactsApplication).pendingSearch.value = name
                                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                            },
                        )
                    }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onBack = back,
                            onOpenList = { open(Screen.SettingsList) },
                            onOpenLanguage = { open(Screen.SettingsLanguage) },
                            onOpenBackup = { open(Screen.SettingsBackup) },
                            onOpenReset = { open(Screen.SettingsReset) },
                            onOpenAbout = { open(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsList> { ListSettingsScreen(onBack = back) }
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
