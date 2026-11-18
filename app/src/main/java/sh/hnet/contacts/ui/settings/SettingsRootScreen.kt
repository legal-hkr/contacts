package sh.hnet.contacts.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.contacts.BuildConfig
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.ui.components.SettingsGroup
import sh.hnet.contacts.ui.components.SettingsNavigationRow
import sh.hnet.contacts.ui.components.SettingsPage

/**
 * The Settings root (SPEC §13.1), a page over the contact list, as Calendar's: the app's own rows
 * first, then App (language), Data (backup, reset) and About on its own. Rows of features not built
 * yet join with their stages.
 */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenList: () -> Unit, onOpenContacts: () -> Unit, onOpenAccounts: () -> Unit, onOpenDuplicates: () -> Unit, onOpenLanguage: () -> Unit, onOpenImport: () -> Unit, onOpenExport: () -> Unit, onOpenBackup: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    // Read on every composition: changing it recreates the activity anyway
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    val app = context.applicationContext as ContactsApplication
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(emptySet())
    // "Shown: 3 of 5" (SPEC §13.1): every account with contacts or able to take them, local contacts when there are any
    // Both kinds of local contacts (no account, the platform's local account) are one "On this phone", as on the Accounts page: one key list each
    val found by produceState<List<List<String>>?>(null) {
        value = runCatching {
            val all = app.contacts.accounts()
            val locals = all.filter { it.local }.map { it.account.key }
            all.filter { !it.local }.map { listOf(it.account.key) } + listOfNotNull(locals.ifEmpty { null })
        }.getOrNull()
    }
    val duplicates by app.duplicates.collectAsStateWithLifecycle()
    val accounts = found?.let { groups -> groups.count { keys -> keys.none { it in hidden } } to groups.size }
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_list, stringResource(R.string.label_settings_list), stringResource(R.string.desc_settings_list), onClick = onOpenList)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_person_add, stringResource(R.string.label_settings_contacts), stringResource(R.string.desc_settings_contacts), onClick = onOpenContacts)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_account_circle, stringResource(R.string.title_accounts), accounts?.let { (shown, all) -> stringResource(R.string.desc_accounts_shown, shown, all) }, onClick = onOpenAccounts)
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, R.drawable.ic_call_merge, stringResource(R.string.title_duplicates),
                    if (duplicates.isEmpty()) stringResource(R.string.desc_no_suggestions) else stringResource(R.string.desc_duplicates_count, duplicates.size),
                    onClick = onOpenDuplicates,
                )
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_download, stringResource(R.string.label_import_file), stringResource(R.string.desc_import_file), onClick = onOpenImport)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_upload, stringResource(R.string.label_export_all), stringResource(R.string.desc_export_all), onClick = onOpenExport)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup), stringResource(R.string.desc_backup_row), onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}
