@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.contacts.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.contacts.R
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.TooltipIconButton

/**
 * The app's root screen. So far only its frame with Settings in the top bar; the contact list
 * views come with the first stage of the UI handoff.
 */
@Composable
fun ContactsScreen(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        // The app's mark is drawn on the launcher icon's canvas, hence the larger size
        EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_in_development), iconSize = 108.dp)
    }
}
