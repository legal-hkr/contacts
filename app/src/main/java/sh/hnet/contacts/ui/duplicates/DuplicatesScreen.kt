@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.DupGroup
import sh.hnet.contacts.model.DupReason
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.TooltipIconButton

/** The chip of a group's reason (SPEC §9.2). */
@Composable
fun reasonText(reason: DupReason): String = stringResource(
    when (reason) {
        DupReason.NUMBER -> R.string.label_reason_number
        DupReason.EMAIL -> R.string.label_reason_email
        DupReason.NAME_EMAIL -> R.string.label_reason_name_email
    },
)

/** The list rows of every contact, by id, for the groups' avatars and names. */
@Composable
fun rememberRows(): Map<Long, ContactRow> {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(emptySet())
    val groups by app.duplicates.collectAsStateWithLifecycle()
    val rows by produceState(emptyMap<Long, ContactRow>(), groups, hidden) {
        value = runCatching { app.contacts.contacts(ListSettings(), hidden).contacts.associateBy { it.id } }.getOrDefault(emptyMap())
    }
    return rows
}

/**
 * Duplicates (SPEC §9.3, the owner's decisions of 17 Nov 2026): an intro, one card per group – two
 * overlapping avatars, the name, the reason – that opens the review; "Merge all" pinned at the
 * bottom only joins every group (with Undo); a permanent merge goes one group at a time.
 */
@Composable
fun DuplicatesScreen(onBack: () -> Unit, onReview: (DupGroup) -> Unit, onJoinAll: (List<DupGroup>) -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val groups by app.duplicates.collectAsStateWithLifecycle()
    val rows = rememberRows()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_duplicates), maxLines = 1) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        if (groups.isEmpty()) {
            EmptyState(R.drawable.ic_done_all, stringResource(R.string.msg_no_duplicates))
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.desc_duplicates_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            items(groups, key = { it.key }) { group -> GroupCard(group, rows) { onReview(group) } }
        }
        Button(
            onClick = { onJoinAll(groups) },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_call_merge), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.button_merge_all))
        }
    }
}

/** One group (SPEC §9.3): two overlapping avatars ("+2" for more), the first contact's name, the reason chip, a chevron. */
@Composable
private fun GroupCard(group: DupGroup, rows: Map<Long, ContactRow>, onClick: () -> Unit) {
    val first = rows[group.ids.first()]
    val second = rows[group.ids.getOrNull(1) ?: -1]
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp).height(40.dp)) {
            if (first != null) Avatar(first.name, first.lookupKey, first.photo, 40.dp, initials = first.initials)
            Box(Modifier.offset(x = 16.dp).border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape)) {
                if (group.ids.size > 2) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.label_more_avatars, group.ids.size - 1), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                    }
                } else if (second != null) {
                    Avatar(second.name, second.lookupKey, second.photo, 40.dp, initials = second.initials)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(first?.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AssistChip(onClick = onClick, label = { Text(reasonText(group.reason), maxLines = 1) })
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
    }
}
