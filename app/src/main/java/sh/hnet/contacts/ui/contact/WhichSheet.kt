@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.settingsRowColors

/**
 * "Which number?" (SPEC §6.3.1, board 05a): the numbers or addresses as on the page, a tap hands
 * the chosen one on and closes the sheet; "Remember as default" makes it the contact's default.
 */
@Composable
fun WhichSheet(name: String, choice: Choice, region: String, onPick: (Detail, Boolean) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    // Opens whole, as the family's sheets
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var remember by rememberSaveable { mutableStateOf(false) }
    val email = choice.action == Action.EMAIL
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(
                stringResource(if (email) R.string.title_which_address else R.string.title_which_number),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
            )
            Text(
                stringResource(
                    when (choice.action) {
                        Action.CALL -> R.string.label_call_to
                        Action.MESSAGE -> R.string.label_message_to
                        Action.EMAIL -> R.string.label_email_to
                    },
                    name,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choice.values.forEachIndexed { index, detail ->
                    SegmentedListItem(
                        onClick = { onPick(detail, remember) },
                        shapes = groupShapes(index, choice.values.size),
                        colors = settingsRowColors(),
                        supportingContent = typeLabel(detail)?.let { { Text(it) } },
                    ) {
                        Text(if (email) detail.value else app.contacts.displayNumber(detail.value, region), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            SegmentedListItem(
                checked = remember,
                onCheckedChange = { remember = it },
                shapes = groupShapes(0, 1),
                colors = settingsRowColors(),
                leadingContent = { Checkbox(checked = remember, onCheckedChange = null) },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.label_remember_default))
            }
        }
    }
}
