@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.editor

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ContactDate
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.ui.contact.dateText

/** A type a value can take: the storage's code and its name; [protocol] for messaging, where the choice is a protocol. */
class TypeOption(val type: Int, @param:StringRes val name: Int, val protocol: Int? = null, val protocolName: String? = null)

/** The types the editor offers per kind (SPEC §7.4.1, owner's decisions of 17 Nov 2026: one Spouse, one Parent; SIP Home, Work, Other). */
val TYPE_OPTIONS: Map<EditKind, List<TypeOption>> = mapOf(
    EditKind.PHONE to listOf(
        TypeOption(Phone.TYPE_MOBILE, R.string.label_type_phone_mobile), TypeOption(Phone.TYPE_HOME, R.string.label_type_phone_home),
        TypeOption(Phone.TYPE_WORK, R.string.label_type_phone_work), TypeOption(Phone.TYPE_FAX_WORK, R.string.label_type_phone_work_fax),
        TypeOption(Phone.TYPE_PAGER, R.string.label_type_phone_pager), TypeOption(Phone.TYPE_OTHER, R.string.label_type_phone_other),
    ),
    EditKind.EMAIL to listOf(TypeOption(Email.TYPE_HOME, R.string.label_type_email_personal), TypeOption(Email.TYPE_WORK, R.string.label_type_email_work), TypeOption(Email.TYPE_OTHER, R.string.label_type_email_other)),
    EditKind.ADDRESS to listOf(TypeOption(StructuredPostal.TYPE_HOME, R.string.label_type_address_home), TypeOption(StructuredPostal.TYPE_WORK, R.string.label_type_address_work), TypeOption(StructuredPostal.TYPE_OTHER, R.string.label_type_address_other)),
    EditKind.WEBSITE to listOf(
        TypeOption(Website.TYPE_HOMEPAGE, R.string.label_type_website_homepage), TypeOption(Website.TYPE_BLOG, R.string.label_type_website_blog),
        TypeOption(Website.TYPE_WORK, R.string.label_type_website_work), TypeOption(Website.TYPE_OTHER, R.string.label_type_website_other),
    ),
    EditKind.DATE to listOf(TypeOption(Event.TYPE_BIRTHDAY, R.string.label_type_date_birthday), TypeOption(Event.TYPE_ANNIVERSARY, R.string.label_type_date_anniversary), TypeOption(Event.TYPE_OTHER, R.string.label_type_date_other)),
    EditKind.RELATION to listOf(
        TypeOption(Relation.TYPE_SPOUSE, R.string.label_type_relation_spouse), TypeOption(Relation.TYPE_PARTNER, R.string.label_type_relation_partner),
        TypeOption(Relation.TYPE_CHILD, R.string.label_type_relation_child), TypeOption(Relation.TYPE_PARENT, R.string.label_type_relation_parent),
        TypeOption(Relation.TYPE_BROTHER, R.string.label_type_relation_brother), TypeOption(Relation.TYPE_SISTER, R.string.label_type_relation_sister),
        TypeOption(Relation.TYPE_FRIEND, R.string.label_type_relation_friend), TypeOption(Relation.TYPE_RELATIVE, R.string.label_type_relation_relative),
        TypeOption(Relation.TYPE_ASSISTANT, R.string.label_type_relation_assistant), TypeOption(Relation.TYPE_MANAGER, R.string.label_type_relation_manager),
    ),
    // XMPP is the storage's Jabber protocol (7), which other apps know; the others are protocols of their own by name (owner, 17 Nov 2026)
    EditKind.IM to listOf(
        TypeOption(0, R.string.label_type_im_matrix, -1, "Matrix"), TypeOption(0, R.string.label_type_im_xmpp, 7),
        TypeOption(0, R.string.label_type_im_signal, -1, "Signal"), TypeOption(0, R.string.label_type_im_telegram, -1, "Telegram"),
    ),
    EditKind.SIP to listOf(TypeOption(1, R.string.label_type_sip_home), TypeOption(2, R.string.label_type_sip_work), TypeOption(3, R.string.label_type_sip_other)),
)

/** The type of [value] as its field shows it: the option's name, or the custom label (for messaging, the protocol's name). */
@Composable
fun typeName(value: FormValue): String {
    val options = TYPE_OPTIONS[value.kind].orEmpty()
    if (value.kind == EditKind.IM) {
        val match = options.firstOrNull { it.protocol == value.protocol && (it.protocol != -1 || it.protocolName == value.label) }
        return match?.let { stringResource(it.name) } ?: value.label ?: sh.hnet.contacts.ui.contact.typeLabel(asDetail(value)).orEmpty()
    }
    if (value.type == 0) return value.label.orEmpty()
    return options.firstOrNull { it.type == value.type }?.let { stringResource(it.name) } ?: sh.hnet.contacts.ui.contact.typeLabel(asDetail(value)).orEmpty()
}

private fun asDetail(value: FormValue) = Detail(
    when (value.kind) {
        EditKind.PHONE -> DetailKind.PHONE
        EditKind.EMAIL -> DetailKind.EMAIL
        EditKind.ADDRESS -> DetailKind.ADDRESS
        EditKind.WEBSITE -> DetailKind.WEBSITE
        EditKind.DATE -> DetailKind.DATE
        EditKind.RELATION -> DetailKind.RELATION
        EditKind.IM -> DetailKind.IM
        EditKind.SIP -> DetailKind.SIP
        else -> DetailKind.NOTE
    },
    value.part("data1"), value.type, value.label, value.isDefault, 0, 0, value.protocol,
)

/**
 * The type field under a value (SPEC §7.4.1, moved from the end of the value's row, where 132 dp
 * held about six characters – the owner, 17 Nov 2026): a tap opens the types and "Custom label…";
 * a long press on a number or e-mail offers "Set as default" / "Remove as default", and the field
 * says ", default" itself, so nothing moves when the default changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TypeField(value: FormValue, onChange: (FormValue) -> Unit, onDefault: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var defaultMenu by remember { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val options = TYPE_OPTIONS[value.kind].orEmpty()
    val name = typeName(value)
    Box(modifier) {
        OutlinedTextField(
            value = if (value.isDefault) stringResource(R.string.label_default_suffix, name) else name,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.label_type), maxLines = 1) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The field itself takes no typing: a tap and a long press go to the box over it
        Box(Modifier.matchParentSize().combinedClickable(onClick = { menu = true }, onLongClick = onDefault?.let { { defaultMenu = true } }))
        DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                val count = options.size + 1
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = MenuDefaults.itemShape(index, count).shape,
                        onClick = {
                            menu = false
                            onChange(if (value.kind == EditKind.IM) value.copy(protocol = option.protocol, label = option.protocolName) else value.copy(type = option.type, label = null))
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.label_custom_label), maxLines = 1) },
                    shape = MenuDefaults.itemShape(options.size, count).shape,
                    onClick = {
                        menu = false
                        custom = true
                    },
                )
            }
        }
        if (onDefault != null) {
            DropdownMenuPopup(expanded = defaultMenu, onDismissRequest = { defaultMenu = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (value.isDefault) R.string.menu_unset_default else R.string.menu_set_default)) },
                        shape = MenuDefaults.itemShape(0, 1).shape,
                        onClick = {
                            defaultMenu = false
                            onDefault(!value.isDefault)
                        },
                    )
                }
            }
        }
    }
    if (custom) {
        CustomLabelDialog(initial = if (value.type == 0 || value.protocol == -1) value.label.orEmpty() else "", onDone = { label ->
            custom = false
            onChange(if (value.kind == EditKind.IM) value.copy(protocol = -1, label = label) else value.copy(type = 0, label = label))
        }, onDismiss = { custom = false })
    }
}

/** "Custom label" (SPEC §7.4.1): the family's form dialog with one field. */
@Composable
private fun CustomLabelDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_custom_label)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(stringResource(R.string.button_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}

/**
 * A date (SPEC §7.5): the M3 date picker with the date's type and the date as it will read in the
 * headline, and "No year" under it, which keeps day and month only. Without a year the picker shows
 * a leap year, so 29 February can be chosen.
 */
@Composable
fun DateDialog(value: FormValue, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val stored = ContactDate.parse(value.part("data1"))
    var noYear by rememberSaveable { mutableStateOf(stored is ContactDate.NoYear) }
    val initial = when (stored) {
        is ContactDate.Full -> stored.date
        is ContactDate.NoYear -> stored.day.atYear(LEAP_YEAR)
        null -> LocalDate.now()
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val picked = state.selectedDateMillis?.let { LocalDate.ofEpochDay(Math.floorDiv(it, DAY_MILLIS)) }
    val text = picked?.let { if (noYear) "--" + DateTimeFormatter.ofPattern("MM-dd").format(MonthDay.from(it)) else it.toString() }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = text != null, onClick = { text?.let(onDone) }) { Text(stringResource(R.string.button_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    ) {
        DatePicker(
            state,
            title = { Text(typeName(value), modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            headline = {
                Column(Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)) {
                    Text(text?.let { dateText(it) }.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(stringResource(R.string.label_no_year), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = noYear, onCheckedChange = { noYear = it })
                    }
                }
            },
        )
    }
}

/** The text a date field shows: as on the page, or nothing. */
@Composable
fun dateFieldText(value: FormValue): String = value.part("data1").takeIf { it.isNotBlank() }?.let { dateText(it) }.orEmpty()

private const val DAY_MILLIS = 86_400_000L
private const val LEAP_YEAR = 2024
