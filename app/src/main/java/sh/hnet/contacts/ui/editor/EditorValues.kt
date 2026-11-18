package sh.hnet.contacts.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.contacts.R
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.ui.components.TooltipIconButton

/** The name's parts in name order (SPEC §7.4.1), and the two always shown. */
private val NAME_PARTS = listOf(
    "data4" to R.string.label_prefix, "data2" to R.string.label_given_name, "data5" to R.string.label_middle_name, "data3" to R.string.label_family_name,
    "data6" to R.string.label_suffix, "data7" to R.string.label_phonetic_given, "data9" to R.string.label_phonetic_family,
)
private val NAME_SHORT = setOf("data2", "data3")

/**
 * One value's fields (SPEC §7.4): the kind's own fields, the type at the end of the first row
 * where the kind has types, and Remove. A read-only value is shown locked, without type or Remove
 * (SPEC §7.6).
 *
 * @param onlyOne The only value of its kind: an empty phone or e-mail stays, without Remove
 */
@Composable
fun ValueFields(value: FormValue, onChange: (FormValue) -> Unit, onRemove: () -> Unit, onDate: () -> Unit, onDefault: (Boolean) -> Unit, onlyOne: Boolean) {
    fun set(column: String, text: String) = onChange(value.copy(parts = value.parts + (column to text)))
    val always = value.kind == EditKind.PHONE || value.kind == EditKind.EMAIL || value.kind == EditKind.NAME
    val removable = !value.readOnly && !(always && onlyOne && value.blank) && value.kind != EditKind.NAME
    val remove: @Composable (() -> Unit)? = if (removable) ({ TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_remove_value), onRemove) }) else null

    if (value.readOnly) {
        ReadOnlyValue(value)
        return
    }
    when (value.kind) {
        EditKind.NAME -> {
            var expanded by rememberSaveable(value.key) { mutableStateOf(value.parts.any { (column, text) -> column !in NAME_SHORT && text.isNotBlank() }) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((column, label) in NAME_PARTS) {
                    if (!expanded && column !in NAME_SHORT) continue
                    Field(value.part(column), { set(column, it) }, label, capitalize = true)
                }
                if (!expanded) {
                    TextButton(onClick = { expanded = true }) {
                        Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.button_more_name_fields))
                    }
                }
            }
        }
        EditKind.ADDRESS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueRow(remove) { Field(value.part("data4"), { set("data4", it) }, R.string.label_street, Modifier.weight(1f), capitalize = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(value.part("data9"), { set("data9", it) }, R.string.label_postcode, Modifier.weight(2f))
                Field(value.part("data7"), { set("data7", it) }, R.string.label_city, Modifier.weight(3f), capitalize = true)
            }
            Field(value.part("data8"), { set("data8", it) }, R.string.label_region, capitalize = true)
            Field(value.part("data10"), { set("data10", it) }, R.string.label_country, capitalize = true)
            TypeField(value, onChange, null, Modifier.fillMaxWidth())
        }
        EditKind.COMPANY -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(value.part("data1"), { set("data1", it) }, R.string.label_company, capitalize = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(value.part("data4"), { set("data4", it) }, R.string.label_job_title, Modifier.weight(1f), capitalize = true)
                remove?.invoke()
            }
        }
        EditKind.DATE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueRow(remove) {
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = dateFieldText(value),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.menu_field_date), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The field takes no typing: a tap opens the picker
                Box(Modifier.matchParentSize().clickable(onClick = onDate))
            }
            }
            TypeField(value, onChange, null, Modifier.fillMaxWidth())
        }
        EditKind.NICKNAME -> Row(verticalAlignment = Alignment.CenterVertically) {
            Field(value.part("data1"), { set("data1", it) }, R.string.menu_field_nickname, Modifier.weight(1f), capitalize = true)
            remove?.invoke()
        }
        EditKind.NOTE -> Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value.part("data1"),
                onValueChange = { set("data1", it) },
                label = { Text(stringResource(R.string.menu_field_note)) },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            remove?.invoke()
        }
        // One field with its type under it: phone, e-mail, website, relation, messaging, SIP
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (label, keyboard) = when (value.kind) {
                EditKind.PHONE -> R.string.label_phone to KeyboardType.Phone
                EditKind.EMAIL -> R.string.label_email to KeyboardType.Email
                EditKind.WEBSITE -> R.string.label_website to KeyboardType.Uri
                EditKind.RELATION -> R.string.menu_field_relation to KeyboardType.Text
                EditKind.IM -> R.string.menu_field_im to KeyboardType.Email
                else -> R.string.menu_field_sip to KeyboardType.Email
            }
            // A default only means something among several (or to take it off the last one left)
            val defaultable = (value.kind == EditKind.PHONE || value.kind == EditKind.EMAIL) && (!onlyOne || value.isDefault)
            ValueRow(remove) { Field(value.part("data1"), { set("data1", it) }, label, Modifier.weight(1f), keyboard = keyboard, capitalize = value.kind == EditKind.RELATION) }
            TypeField(value, onChange, if (defaultable) onDefault else null, Modifier.fillMaxWidth())
        }
    }
}

/** A value's first row with Remove at its end (the owner, 17 Nov 2026: after the value, not after its type). */
@Composable
private fun ValueRow(remove: (@Composable () -> Unit)?, field: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        field()
        remove?.invoke()
    }
}

@Composable
private fun Field(
    text: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = false,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None),
        // Full width unless the row gives the field its own share
        modifier = if (modifier == Modifier) Modifier.fillMaxWidth() else modifier,
    )
}

/** A value from a read-only entry (SPEC §7.6): the component's disabled field with a lock, its type in the supporting line. */
@Composable
private fun ReadOnlyValue(value: FormValue) {
    val text = value.parts.values.filter { it.isNotBlank() }.joinToString(", ")
    val type = typeName(value)
    OutlinedTextField(
        value = if (value.kind == EditKind.DATE) dateFieldText(value) else text,
        onValueChange = {},
        enabled = false,
        singleLine = true,
        trailingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
        supportingText = { Text(if (type.isNotEmpty()) stringResource(R.string.label_read_only_value, type) else stringResource(R.string.label_read_only)) },
        modifier = Modifier.fillMaxWidth(),
    )
}
