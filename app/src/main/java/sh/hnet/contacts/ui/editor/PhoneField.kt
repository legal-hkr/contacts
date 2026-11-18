@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.editor

import android.app.LocaleManager
import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import sh.hnet.contacts.R
import sh.hnet.contacts.model.PhoneFormat
import sh.hnet.contacts.ui.components.menuScroll

/**
 * The phone number in the editor (the owner's decisions of 18 Nov 2026): the country's flag at the field's start – a globe while there is none – opens the menu of countries (a plain M3 Expressive menu, the owner's choice over a searchable sheet); the chosen one's calling code goes to the number's start, replacing one already there, and a number with a code is formatted as its country writes it while it is typed. No country is chosen by default and none is needed (112); a stored number with "+" shows its flag at once.
 */
@Composable
fun PhoneField(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Changed from outside the field (the form read again): the field follows, the cursor at the end
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    // The country picked from the list, for a code several countries share (+1 Canada, not the United States)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val region = PhoneFormat.regionOf(field.text, chosen)
    val country = region?.let { remember(it, locale) { PhoneFormat.country(it, locale) } }
    val description = country?.let { stringResource(R.string.content_description_country, it.name) } ?: stringResource(R.string.title_country_code)

    OutlinedTextField(
        value = field,
        onValueChange = { edited ->
            if (edited.text == field.text) {
                // Only the cursor or the selection moved
                field = edited
            } else {
                val (formatted, cursor) = PhoneFormat.typed(field.text, edited.text, edited.selection.end, chosen)
                field = TextFieldValue(formatted, TextRange(cursor))
                onChange(formatted)
            }
        },
        singleLine = true,
        label = { Text(stringResource(R.string.label_phone), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Box {
                IconButton(onClick = { picking = true }, modifier = Modifier.semantics { contentDescription = description }) {
                    if (country != null) Text(country.flag, fontSize = 20.sp) else Icon(painterResource(R.drawable.ic_public), contentDescription = null)
                }
                CountryMenu(picking, region, pinnedRegions(context), locale, onDismiss = { picking = false }) { picked ->
                    picking = false
                    chosen = picked?.region
                    val changed = if (picked == null) PhoneFormat.withoutCountry(field.text) else PhoneFormat.withCountry(field.text, picked.region)
                    field = TextFieldValue(changed, TextRange(changed.length))
                    onChange(changed)
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = modifier,
    )

}

/**
 * The countries pinned at the menu's top (the owner, 18 Nov 2026): the country of the mobile network, then the region the phone's settings name (System › Languages, the region of the first language) – in that order, the owner's –, once when they are the same; either left out when unknown.
 */
private fun pinnedRegions(context: Context): List<String> {
    val settings = context.getSystemService(LocaleManager::class.java).systemLocales.get(0)?.country
    val network = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso
    return listOfNotNull(network, settings).map { it.uppercase(Locale.ROOT) }.filter { it.length == 2 }.distinct()
}

/**
 * The countries' menu at the flag, as the editor's other menus (M3 Expressive groups): "No country code" while the number has one, the countries of the mobile network and of the phone's region setting (one when they are the same), then every country by name, each as "Polska (+48)" after its flag.
 */
@Composable
private fun CountryMenu(expanded: Boolean, current: String?, pinned: List<String>, locale: Locale, onDismiss: () -> Unit, onPick: (PhoneFormat.Country?) -> Unit) {
    val countries = remember(locale) { PhoneFormat.countries(locale) }
    val pinnedCountries = remember(countries, pinned) { pinned.mapNotNull { region -> countries.firstOrNull { it.region == region } } }
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.menuScroll()) {
        val groups = listOfNotNull(
            if (current != null) listOf<PhoneFormat.Country?>(null) else null,
            pinnedCountries.ifEmpty { null },
            countries,
        )
        groups.forEachIndexed { group, items ->
            if (group > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(group, groups.size)) {
                items.forEachIndexed { index, country ->
                    DropdownMenuItem(
                        text = { Text(country?.let { "${it.name} (+${it.code})" } ?: stringResource(R.string.label_no_country_code), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            if (country != null) Text(country.flag, fontSize = 20.sp) else Icon(painterResource(R.drawable.ic_public), contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        shape = MenuDefaults.itemShape(index, items.size).shape,
                        onClick = { onPick(country) },
                    )
                }
            }
        }
    }
}
