package moe.fuck.hyperos.card

internal object CardSettings {
    const val PACKAGE = "com.miui.tsmclient"
    const val PREFERENCES = "card"
    const val ENABLED = "enabled"
}

internal data class CardBinding(val aid: String, val image: String, val urls: Set<String>)

// 有卡片身份时只按 AID 匹配，避免多张门卡共用官方图片导致串卡。
internal fun selectCardImage(aid: String?, url: String?, bindings: Collection<CardBinding>): String? {
    if (!aid.isNullOrBlank()) return bindings.singleOrNull { it.aid == aid }?.image
    if (url.isNullOrBlank()) return null
    fun unique(matches: List<CardBinding>): String? = matches.map { it.image }.distinct().singleOrNull()
    val exact = bindings.filter { url in it.urls }
    if (exact.isNotEmpty()) return unique(exact)
    val name = cardUrlName(url) ?: return null
    return unique(bindings.filter { binding -> binding.urls.any { cardUrlName(it) == name } })
}

internal fun cardUrlName(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    if (!path.contains('/')) return null
    return path.substringAfterLast('/').takeIf { it.isNotBlank() }
}

internal fun validCardImageName(name: String): Boolean =
    name.matches(Regex("[a-f0-9]{32}\\.png"))

internal fun unsharedCardUrls(card: WalletCard, cards: Collection<WalletCard>): Set<String> =
    card.urls.filterTo(linkedSetOf()) { url ->
        cards.none { other -> other.aid != card.aid && other.urls.any {
            it == url || (cardUrlName(url) != null && cardUrlName(it) == cardUrlName(url))
        } }
    }

internal const val MAX_CARD_IMAGE_BYTES = 16L * 1024 * 1024

internal fun copyCardImage(input: java.io.InputStream, output: java.io.OutputStream) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= MAX_CARD_IMAGE_BYTES) { "图片不能超过 16 MiB" }
        output.write(buffer, 0, count)
    }
    require(total > 0) { "图片为空" }
}
