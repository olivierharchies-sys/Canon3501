package be.canonhub

import java.text.Normalizer

/** Types de document reconnus, dossier de rangement et mots-clés (sans accents, minuscules). */
enum class DocType(val label: String, val folder: String, val keys: List<String>) {
    Invoice("Facture", "Factures", listOf(
        "facture", "invoice", "tva", "htva", "total ttc", "montant a payer", "a payer avant", "echeance",
        "numero de facture", "n de facture", "date de facture", "communication structuree", "bon de commande",
    )),
    Receipt("Ticket", "Tickets", listOf(
        "ticket", "caisse", "merci de votre visite", "bancontact", "carte bancaire", "rendu", "a emporter",
        "transaction", "terminal", "payconiq",
    )),
    Payslip("Fiche de paie", "Paie", listOf(
        "fiche de paie", "bulletin de paie", "salaire brut", "net a payer", "onss", "remuneration",
        "precompte professionnel", "employeur", "pecule",
    )),
    Tax("Impôts", "Impôts", listOf(
        "spf finances", "avertissement-extrait de role", "extrait de role", "impot", "myminfin", "tax-on-web",
        "declaration fiscale", "revenus imposables", "precompte immobilier", "contributions",
    )),
    Bank("Banque", "Banque", listOf(
        "extrait de compte", "releve de compte", "iban", "bic", "solde", "virement", "domiciliation",
        "nouveau solde", "ancien solde", "carte de debit", "credit hypothecaire",
    )),
    Insurance("Assurance", "Assurances", listOf(
        "assurance", "police n", "numero de police", "prime", "sinistre", "assure", "preneur d'assurance",
        "franchise", "omnium", "responsabilite civile",
    )),
    Health("Santé", "Santé", listOf(
        "mutualite", "mutuelle", "ordonnance", "prescription", "docteur", "medecin", "patient", "inami",
        "pharmacie", "hopital", "clinique", "consultation", "vignette", "remboursement soins",
    )),
    Contract("Contrat", "Contrats", listOf(
        "contrat", "entre les soussignes", "conditions generales", "conditions particulieres", "article 1",
        "lu et approuve", "bail", "locataire", "bailleur", "avenant",
    )),
    Official("Administratif", "Administratif", listOf(
        "administration communale", "commune de", "service public", "spf", "attestation", "certificat",
        "carte d'identite", "passeport", "permis de conduire", "registre national", "etat civil",
    )),
    Warranty("Garantie", "Garanties", listOf(
        "garantie", "warranty", "mode d'emploi", "notice", "numero de serie", "serial number", "conformite",
    )),
    Letter("Courrier", "Courrier", listOf(
        "madame", "monsieur", "veuillez agreer", "cordialement", "objet :", "concerne :", "salutations",
        "nos references", "votre reference",
    )),
    Photo("Photo", "Photos", emptyList()),
    Other("Document", "Divers", emptyList()),
}

/** Classement local (OCR ML Kit sur l'appareil + mots-clés) : type, dossier et nom proposé. */
object Classify {
    data class Guess(val type: DocType, val issuer: String?, val date: String?) {
        fun name(t: DocType = type): String = clean(listOfNotNull(t.label, issuer, date ?: today()).joinToString("_"))
    }

    private val months = listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre",
    )

    fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE).format(java.util.Date())

    /** Nom de fichier sûr : lettres, chiffres, tirets, soulignés ; 60 caractères max. */
    fun clean(s: String): String =
        s.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").replace(Regex("-{2,}"), "-").trim('-', '_').take(60)

    fun guess(text: String): Guess {
        val t = plain(text)
        val type = if (t.count { it.isLetter() } < 25) DocType.Photo else {
            DocType.entries
                .filter { it.keys.isNotEmpty() }
                .map { d -> d to d.keys.sumOf { k -> Regex(Regex.escape(k)).findAll(t).count().coerceAtMost(3) } }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }?.first ?: DocType.Other
        }
        return Guess(type, if (type == DocType.Photo) null else issuer(text), date(t))
    }

    /** Date du document : première date plausible trouvée (jj/mm/aaaa ou « 12 mars 2026 »). */
    private fun date(t: String): String? {
        Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2}|\\d{4})\\b").findAll(t).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            if (d in 1..31 && mo in 1..12 && y in 1990..2100) return "%04d-%02d-%02d".format(y, mo, d)
        }
        Regex("\\b(\\d{1,2})(?:er)?\\s+(${months.joinToString("|")})\\s+(\\d{4})\\b").find(t)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = months.indexOf(m.groupValues[2]) + 1; val y = m.groupValues[3].toInt()
            if (d in 1..31 && y in 1990..2100) return "%04d-%02d-%02d".format(y, mo, d)
        }
        return null
    }

    /** Émetteur probable : première ligne courte et « nominale » en haut du document. */
    private fun issuer(text: String): String? {
        val skip = Regex("facture|invoice|date|page|ticket|madame|monsieur|objet|concerne|tel|fax|www|@|rue|avenue|\\d{4,}")
        return text.lines().asSequence().take(12)
            .map { it.trim() }
            .filter { l -> l.length in 3..40 && l.count { it.isLetter() } >= 3 && !skip.containsMatchIn(plain(l)) }
            .firstOrNull()
            ?.let { l -> clean(l.split(Regex("\\s+")).take(3).joinToString(" ").lowercase().replaceFirstChar { it.uppercase() }) }
            ?.takeIf { it.length >= 3 }
    }
}
