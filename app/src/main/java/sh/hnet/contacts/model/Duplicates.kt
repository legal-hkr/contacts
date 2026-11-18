package sh.hnet.contacts.model

/**
 * A contact as the duplicate search sees it (SPEC §9.2): its name folded (case and diacritics), the
 * keys of its numbers (digits in international form), its e-mails in lower case, and its other
 * values (addresses, websites) trimmed.
 */
data class DupContact(val id: Long, val name: String, val numbers: Set<String>, val emails: Set<String>, val others: Set<String>)

/** Why a group was suggested (SPEC §9.2): the first rule that matched; the same name counts only with another value in common, shown as "Same name and email" (the SPEC's "name and number" can't occur: a shared number matches the first rule). */
enum class DupReason { NUMBER, EMAIL, NAME_EMAIL }

/** Contacts that look like one person. */
data class DupGroup(val ids: List<Long>, val reason: DupReason) {
    /** Stable while the same contacts are in it, for keys and for "Not now". */
    val key: String get() = ids.sorted().joinToString(",")
}

object Duplicates {
    /** A group larger than this is more likely a shared office number than one person (SPEC §9.2). */
    const val MAX_GROUP = 5

    /** Numbers shorter than this (short codes, extensions) say nothing about a person. */
    private const val MIN_DIGITS = 7

    /**
     * Groups of contacts that look like one person (SPEC §9.2): they share a number or an e-mail,
     * or have the same name and share any value; a name alone is never enough. Pairs in [separate]
     * (the storage's "keep separate", given as contact id pairs) are never joined, not even through a third contact. Groups are
     * transitive (A like B and B like C make one group {A, B, C}) and over [MAX_GROUP] dropped.
     */
    fun find(contacts: List<DupContact>, separate: Set<Pair<Long, Long>>): List<DupGroup> {
        val apart = separate.flatMap { (a, b) -> listOf(a to b, b to a) }.toSet()
        val parent = HashMap<Long, Long>()
        fun root(id: Long): Long {
            var r = id
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            parent[id] = r
            return r
        }
        val reasons = HashMap<Pair<Long, Long>, DupReason>()
        fun link(a: Long, b: Long, reason: DupReason) {
            if (a == b || (a to b) in apart) return
            val pair = minOf(a, b) to maxOf(a, b)
            val known = reasons[pair]
            if (known == null || reason.ordinal < known.ordinal) reasons[pair] = reason
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        // Contacts sharing a value, found through an index of each value rather than every pair
        fun byValue(values: (DupContact) -> Set<String>, reason: (DupContact, DupContact) -> DupReason?) {
            val index = HashMap<String, MutableList<DupContact>>()
            for (contact in contacts) for (value in values(contact)) index.getOrPut(value) { ArrayList() } += contact
            for (sharing in index.values) {
                if (sharing.size < 2 || sharing.size > MAX_GROUP * 2) continue
                for (i in sharing.indices) for (j in i + 1 until sharing.size) reason(sharing[i], sharing[j])?.let { link(sharing[i].id, sharing[j].id, it) }
            }
        }
        val sameName = { a: DupContact, b: DupContact -> a.name.isNotEmpty() && a.name == b.name }
        // Rules in the SPEC's order; a pair's reason is the first that matched
        byValue({ it.numbers.filter { n -> n.length >= MIN_DIGITS }.toSet() }) { _, _ -> DupReason.NUMBER }
        byValue({ it.emails }) { _, _ -> DupReason.EMAIL }
        // The same name with an address or a website in common (a number or an e-mail matched above already)
        byValue({ it.others }) { a, b -> if (sameName(a, b)) DupReason.NAME_EMAIL else null }
        // A group reached through a third contact may hold a pair the user said are not the same person: joining it would join them too, so it isn't suggested
        val groups = contacts.map { it.id }.filter { parent.containsKey(it) }.groupBy { root(it) }.values
            .filter { it.size in 2..MAX_GROUP }
            .filter { ids -> ids.none { a -> ids.any { b -> (a to b) in apart } } }
        return groups.map { ids ->
            val inside = ids.toSet()
            val reason = reasons.filterKeys { (a, b) -> a in inside && b in inside }.values.minByOrNull { it.ordinal } ?: DupReason.NUMBER
            DupGroup(ids.sorted(), reason)
        }.sortedBy { it.ids.first() }
    }

    /** A name for comparing: lower case, diacritics and ł folded, spaces collapsed. */
    fun foldName(name: String): String = buildString {
        for (c in name.trim().lowercase()) append(if (c == 'ł') 'l' else java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).first())
    }.replace(Regex("\\s+"), " ")
}
