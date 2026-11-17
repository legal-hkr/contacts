package sh.hnet.contacts.ui.contact

import android.icu.text.DateTimePatternGenerator
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ContactDate
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind

/** The storage's messaging protocols by code (its constants are deprecated; the codes stay in old data). */
private val PROTOCOLS = mapOf(0 to "AIM", 1 to "MSN", 2 to "Yahoo", 3 to "Skype", 4 to "QQ", 5 to "Google Talk", 6 to "ICQ", 7 to "XMPP", 8 to "NetMeeting")

/**
 * The type of a value as the page shows it (SPEC §6.4): the app's own name of the storage's type in
 * the app's language, the user's label for a custom one, and the system's name for the rare types
 * the app doesn't name itself.
 */
@Composable
fun typeLabel(detail: Detail): String? {
    val resources = LocalResources.current
    if (detail.type == 0 && detail.kind != DetailKind.IM && detail.kind != DetailKind.NOTE) return detail.label
    val ours: Int? = when (detail.kind) {
        DetailKind.PHONE -> when (detail.type) {
            Phone.TYPE_MOBILE -> R.string.label_type_phone_mobile
            Phone.TYPE_HOME -> R.string.label_type_phone_home
            Phone.TYPE_WORK -> R.string.label_type_phone_work
            Phone.TYPE_FAX_WORK -> R.string.label_type_phone_work_fax
            Phone.TYPE_FAX_HOME -> R.string.label_type_phone_home_fax
            Phone.TYPE_PAGER -> R.string.label_type_phone_pager
            Phone.TYPE_MAIN -> R.string.label_type_phone_main
            Phone.TYPE_OTHER -> R.string.label_type_phone_other
            else -> null
        }
        DetailKind.EMAIL -> when (detail.type) {
            Email.TYPE_HOME -> R.string.label_type_email_personal
            Email.TYPE_WORK -> R.string.label_type_email_work
            Email.TYPE_OTHER -> R.string.label_type_email_other
            else -> null
        }
        DetailKind.ADDRESS -> when (detail.type) {
            StructuredPostal.TYPE_HOME -> R.string.label_type_address_home
            StructuredPostal.TYPE_WORK -> R.string.label_type_address_work
            StructuredPostal.TYPE_OTHER -> R.string.label_type_address_other
            else -> null
        }
        DetailKind.WEBSITE -> when (detail.type) {
            Website.TYPE_HOMEPAGE -> R.string.label_type_website_homepage
            Website.TYPE_BLOG -> R.string.label_type_website_blog
            Website.TYPE_WORK -> R.string.label_type_website_work
            Website.TYPE_OTHER -> R.string.label_type_website_other
            else -> R.string.label_website
        }
        DetailKind.DATE -> when (detail.type) {
            Event.TYPE_BIRTHDAY -> R.string.label_type_date_birthday
            Event.TYPE_ANNIVERSARY -> R.string.label_type_date_anniversary
            else -> R.string.label_type_date_other
        }
        DetailKind.RELATION -> when (detail.type) {
            Relation.TYPE_SPOUSE -> R.string.label_type_relation_spouse
            Relation.TYPE_PARTNER -> R.string.label_type_relation_partner
            Relation.TYPE_CHILD -> R.string.label_type_relation_child
            Relation.TYPE_PARENT -> R.string.label_type_relation_parent
            Relation.TYPE_MOTHER -> R.string.label_type_relation_mother
            Relation.TYPE_FATHER -> R.string.label_type_relation_father
            Relation.TYPE_BROTHER -> R.string.label_type_relation_brother
            Relation.TYPE_SISTER -> R.string.label_type_relation_sister
            Relation.TYPE_FRIEND -> R.string.label_type_relation_friend
            Relation.TYPE_RELATIVE -> R.string.label_type_relation_relative
            Relation.TYPE_ASSISTANT -> R.string.label_type_relation_assistant
            Relation.TYPE_MANAGER -> R.string.label_type_relation_manager
            Relation.TYPE_DOMESTIC_PARTNER -> R.string.label_type_relation_domestic_partner
            Relation.TYPE_REFERRED_BY -> R.string.label_type_relation_referred_by
            else -> null
        }
        // The deprecated SipAddress kind's type codes: 1 home, 2 work, 3 other
        DetailKind.SIP -> when (detail.type) {
            1 -> R.string.label_type_sip_home
            2 -> R.string.label_type_sip_work
            else -> R.string.label_type_sip_other
        }
        // Messaging: the protocol, or the user's own name of it
        DetailKind.IM -> return if (detail.protocol == -1) detail.label else detail.protocol?.let { PROTOCOLS[it] } ?: detail.label
        DetailKind.NOTE -> return null
    }
    if (ours != null) return stringResource(ours)
    return when (detail.kind) {
        DetailKind.PHONE -> Phone.getTypeLabel(resources, detail.type, detail.label).toString()
        DetailKind.EMAIL -> Email.getTypeLabel(resources, detail.type, detail.label).toString()
        DetailKind.ADDRESS -> StructuredPostal.getTypeLabel(resources, detail.type, detail.label).toString()
        DetailKind.RELATION -> Relation.getTypeLabel(resources, detail.type, detail.label).toString()
        else -> detail.label
    }
}

/** A stored date in the app's language (SPEC §6.4.1): "4 czerwca 2016", or "12 marca" without a year; as stored when it can't be read. */
@Composable
fun dateText(value: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (val date = ContactDate.parse(value)) {
        is ContactDate.Full -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date.date)
        is ContactDate.NoYear -> DateTimeFormatter.ofPattern(dayMonthPattern(locale), locale).format(date.day)
        null -> value
    }
}

private fun dayMonthPattern(locale: Locale): String = DateTimePatternGenerator.getInstance(locale).getBestPattern("MMMMd")
