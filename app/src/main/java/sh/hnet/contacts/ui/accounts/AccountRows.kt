@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.accounts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.text.NumberFormat
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.ui.components.RowIconCircle
import sh.hnet.contacts.ui.components.settingsRowColors

/** The account app's own icon, as the system's account settings show it; null when the app doesn't give one. */
@Composable
private fun rememberAccountIcon(account: AccountInfo): ImageBitmap? {
    val context = LocalContext.current
    return remember(account.appPackage, account.iconId) {
        val pkg = account.appPackage ?: return@remember null
        if (account.local || account.iconId == 0) return@remember null
        try {
            context.packageManager.getDrawable(pkg, account.iconId, null)?.toBitmap(96, 96)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

/** The 40 dp circle at a row's start (SPEC §8.1, the shape as [RowIcon]): the account app's icon, `cloud_sync` when it has none, `smartphone` for local contacts. */
@Composable
fun AccountIcon(account: AccountInfo) {
    val icon = rememberAccountIcon(account)
    RowIconCircle {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            Icon(painterResource(if (account.local) R.drawable.ic_smartphone else R.drawable.ic_cloud_sync), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** The account's name as rows show it: its own name, or "On this phone" for local contacts. */
@Composable
fun accountName(account: AccountInfo): String = if (account.local) stringResource(R.string.label_this_phone) else account.account.name

/** "DAVx⁵, contacts: 412", "DAVx⁵, no contacts", or "Local contacts: 12" (SPEC §8.1, §8.3). */
@Composable
fun accountSummary(account: AccountInfo): String {
    val count = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(account.count)
    return when {
        account.local -> stringResource(R.string.label_local_count, count)
        account.count == 0 -> stringResource(R.string.label_account_empty, account.app ?: account.account.type)
        else -> stringResource(R.string.label_account_count, account.app ?: account.account.type, count)
    }
}

/**
 * One account with the switch that shows its contacts in the list (SPEC §8.1); the whole row
 * toggles. Read-only accounts say so on a second line.
 */
@Composable
fun AccountSwitchRow(shapes: ListItemShapes, account: AccountInfo, shown: Boolean, onShownChange: (Boolean) -> Unit) {
    val summary = accountSummary(account)
    val readOnly = stringResource(R.string.label_read_only)
    SegmentedListItem(
        checked = shown,
        onCheckedChange = onShownChange,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { AccountIcon(account) },
        trailingContent = { Switch(checked = shown, onCheckedChange = null) },
        supportingContent = { Text(if (account.writable) summary else "$summary\n$readOnly") },
    ) {
        Text(accountName(account), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
