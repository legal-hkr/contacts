package sh.hnet.contacts.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Contacts' backup file (SPEC §13.4, as the owner narrowed it on 17 Nov 2026), shaped as Calendar's
 * and the rest of the family's: the settings and the accounts' visibility. Nothing taken from
 * contacts is in it – no contacts, no answers about duplicates, nothing derived from lookup keys:
 * contacts are the system's, kept by the accounts or by the vCard export. Pure Kotlin, unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
    val accounts: List<BackupAccount> = emptyList(),
) {
    companion object {
        const val FORMAT = "sh.hnet.contacts.backup"
        const val FORMAT_VERSION = 1
    }
}

/** Every setting may be absent (a backup from before it existed): a restore then leaves the default. */
@Serializable
data class BackupSettings(
    /** "first" or "last". */
    val sortBy: String? = null,
    /** "first_last" or "last_first". */
    val nameOrder: String? = null,
    val companyLine: Boolean? = null,
    /** "many" or "theme". */
    val avatarColors: String? = null,
    /** The account new contacts are saved to, as the storage names it; empty type and name for local contacts. */
    val defaultAccountType: String? = null,
    val defaultAccountName: String? = null,
)

/** Whether an account's contacts are shown in the list; [type] and [name] as the storage names the account, empty for local contacts. */
@Serializable
data class BackupAccount(val type: String, val name: String, val shown: Boolean)

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}
