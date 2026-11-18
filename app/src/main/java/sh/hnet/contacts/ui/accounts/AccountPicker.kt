@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo

/** The account a contact goes to (SPEC §7.2, §10.2): a read-only field whose menu lists [targets]. */
@Composable
fun AccountPicker(label: String, current: AccountInfo, targets: List<AccountInfo>, onChoose: (AccountInfo) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    val shown = accountName(current) + (current.app?.let { " ($it)" } ?: "")
    Box(modifier) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            leadingIcon = { Icon(painterResource(if (current.local) R.drawable.ic_smartphone else R.drawable.ic_cloud_sync), contentDescription = null) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { menu = true })
        DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                targets.forEachIndexed { index, target ->
                    DropdownMenuItem(
                        text = { Text(accountName(target) + (target.app?.let { " ($it)" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(painterResource(if (target.local) R.drawable.ic_smartphone else R.drawable.ic_cloud_sync), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        shape = MenuDefaults.itemShape(index, targets.size).shape,
                        onClick = {
                            menu = false
                            onChoose(target)
                        },
                    )
                }
            }
        }
    }
}
