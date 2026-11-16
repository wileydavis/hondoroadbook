package fold.core

/**
 * What the apps believe about a download. The pack catalog (catalog.json) is signed by the packs workflow with a
 * P-256 key kept as a repository secret (CATALOG_SIGNING_KEY); its signature, DER, sits beside it as catalog.json.sig.
 * The apps check it against [PUBLIC_KEY] before using the catalog, and each pack against the SHA-256 the catalog gives
 * for it. So a tampered site, bucket or mirror cannot hand a phone a pack the workflow did not build.
 * (A pack the rider opens from a file is their choice, and is read as untrusted input like any GPX.)
 */
object CatalogTrust {
    /** SubjectPublicKeyInfo (X.509), DER, base64: the public half of the catalog signing key */
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+iQBlstoL6q21l1SleTxUkxWS3WMzb3KO8dMnQxfNR5WFX1PSbGPXF2LhPKYe88pdgDZoEmYUZd9yjw/qS2Jcw=="
    const val SIGNATURE_SUFFIX = ".sig"

    /** a SHA-256 as the catalog writes it: 64 lowercase hex digits */
    fun isHash(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
    fun hex(b: ByteArray): String { val h = "0123456789abcdef"; val sb = StringBuilder(b.size * 2); for (x in b) { val v = x.toInt() and 0xFF; sb.append(h[v shr 4]).append(h[v and 15]) }; return sb.toString() }
}
