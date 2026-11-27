@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.ui.components.SettingsGroup
import sh.hnet.contacts.ui.components.SettingsPage
import sh.hnet.contacts.ui.components.SettingsSwitchRow
import sh.hnet.contacts.ui.components.settingsRowColors

/**
 * Settings › List (SPEC §13.2): sort by first or last name and the order names are shown in, each
 * as connected toggle buttons under its title, and the company on a second line. They apply to the
 * list at once.
 */
@Composable
fun ListSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val settings by app.preferences.listSettings.collectAsStateWithLifecycle(ListSettings())
    val scope = rememberCoroutineScope()
    SettingsPage(title = stringResource(R.string.label_settings_list), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_sort_by), stringResource(R.string.label_sort_first), stringResource(R.string.label_sort_last), second = settings.sortByLast) {
                    scope.launch { app.preferences.setSortByLast(it) }
                }
            }
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_show_names), stringResource(R.string.label_name_first_last), stringResource(R.string.label_name_last_first), second = settings.lastFirst) {
                    scope.launch { app.preferences.setLastFirst(it) }
                }
            }
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_avatar_colors), stringResource(R.string.label_avatar_colors_many), stringResource(R.string.label_avatar_colors_theme), second = !settings.avatarColors) {
                    scope.launch { app.preferences.setAvatarColors(!it) }
                }
            }
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_list, stringResource(R.string.label_company_line), settings.companyLine, { scope.launch { app.preferences.setCompanyLine(it) } }, stringResource(R.string.desc_company_line))
            }
        }
    }
}

/** A setting of two choices: its title, then the choices as two connected toggle buttons; [onChoose] gets whether the second was chosen. */
@Composable
private fun ChoiceRow(shapes: ListItemShapes, title: String, first: String, secondLabel: String, second: Boolean, onChoose: (Boolean) -> Unit) {
    SegmentedListItem(shapes = shapes, colors = settingsRowColors()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text(title)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                listOf(first to false, secondLabel to true).forEachIndexed { index, (label, value) ->
                    ToggleButton(
                        checked = second == value,
                        onCheckedChange = { if (it) onChoose(value) },
                        shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        // One container level above the row (the owner, 27 Nov 2026): M3's unchecked surfaceContainer is the row's own color, so the choice not taken vanished into it
                        colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
