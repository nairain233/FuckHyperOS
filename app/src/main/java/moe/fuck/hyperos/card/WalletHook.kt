package moe.fuck.hyperos.card

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentProvider
import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface.Invoker
import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.ReloadableHooks
import moe.fuck.hyperos.MainThread
import moe.fuck.hyperos.LogReporter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class WalletHook(
    private val module: XposedModule,
    private val hooks: ReloadableHooks,
    private val loader: ClassLoader,
    private val active: () -> Boolean,
    private val report: LogReporter,
) : AutoCloseable {
    private lateinit var targets: CardTargets
    private val lock = Any()
    @Volatile private var center: CardCenter? = null
    @Volatile private var retired = false

    fun install() {
        targets = CardDexResolver.resolve(loader, report)
        val provider = Class.forName("com.miui.tsmclient.database.TSMPublicProvider", false, loader)
            .getDeclaredMethod("call", String::class.java, String::class.java, Bundle::class.java)
        hooks.intercept(provider) { chain ->
            if (retired || chain.args[0] != CardProtocol.SERVICE) chain.proceed()
            else {
                val context = checkNotNull((chain.thisObject as ContentProvider).context)
                Bundle().apply { putBinder(CardProtocol.SERVICE, service(context)) }
            }
        }
        hooks.intercept(targets.imageUrl) { chain ->
            val url = chain.args[0] as? String
            val replacement = if (active() && !retired) attempt("image-url") {
                val store = service().store
                if (store.localFile(url) != null) url else store.replacement(null, url)
            } else null
            replacement ?: chain.proceed()
        }
        listOf("com.miui.tsmclient.entity.MifareCardInfo" to "getImageUrl",
            "com.miui.tsmclient.entity.CardHolderDeviceCardInfo" to "getCardArt").forEach { (type, name) ->
            val method = cardMethod(Class.forName(type, false, loader), name)
            check(method.returnType == String::class.java)
            hooks.intercept(method) { chain ->
                val result = chain.proceed()
                if (!active() || retired) result else attempt("card-art") {
                    service().store.replacement(aid(chain.thisObject), result as? String)
                } ?: result
            }
        }
        hooks.intercept(targets.islandImage) { chain ->
            val result = chain.proceed()
            if (!active() || retired) result else attempt("island-card") {
                // 门卡按 AID 配置；原版只拿 mCardArt URL 查配置，因键不同而漏替换。
                service().store.replacement(aid(chain.args[0]), result as? String)
                    ?.also { report("CARD_ISLAND_REPLACED") }
            } ?: result
        }
        hooks.intercept(targets.islandLoader) { chain ->
            val icon = if (active() && !retired) attempt("island-bitmap") {
                service(chain.args[0] as Context).store.localFile(chain.args[1] as? String)
                    ?.let { CardImages.islandIcon(chain.args[0] as Context, it) }
            } else null
            if (icon == null) chain.proceed() else {
                // 只接管自有卡面的解码，继续使用钱包原回调生成通知、RemoteViews 和超级岛参数。
                val callback = chain.args[2]
                MainThread.run {
                    try {
                        targets.islandCallback.apply { isAccessible = true }.invoke(callback, icon)
                    } catch (error: InvocationTargetException) {
                        throw error.targetException
                    }
                }
                report("CARD_ISLAND_BITMAP_READY")
                null
            }
        }
        // getImageUrl 等小方法可能被内联；显式处理已核实的通知直接调用方。
        targets.islandCallers.forEach { method ->
            attempt("island-deopt") { report("CARD_ISLAND_DEOPT success=${module.deoptimize(method)}"); Unit }
        }
        report("CARD_HOOK_READY")
    }

    private fun <T> attempt(label: String, action: () -> T): T? = try { action() }
    catch (error: Exception) {
        report.error("CARD_SKIPPED feature=$label type=${error.javaClass.simpleName}")
        null
    } catch (error: LinkageError) {
        report.error("CARD_SKIPPED feature=$label type=${error.javaClass.simpleName}")
        null
    }

    @SuppressLint("PrivateApi")
    private fun service(context: Context? = null): CardCenter = center ?: synchronized(lock) {
        check(!retired) { "Wallet hook retired" }
        center ?: run {
            val app = context?.applicationContext ?: Class.forName("android.app.ActivityThread", false, loader)
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
            checkNotNull(app) { "Wallet context unavailable" }
            check(app.packageName == CardSettings.PACKAGE)
            CardCenter(CardStore(app), { loadCards(app) }, active, module.moduleApplicationInfo.uid, report)
                .also { center = it }
        }
    }

    private fun original(method: Method, receiver: Any?, vararg args: Any?): Any? =
        module.getInvoker(method.apply { isAccessible = true }).setType(Invoker.Type.ORIGIN).invoke(receiver, *args)

    private fun read(receiver: Any?, name: String): Any? = receiver?.let { original(cardMethod(it.javaClass, name), it) }

    private fun aid(receiver: Any?): String? = read(receiver, "getAid") as? String

    private fun urls(card: Any?): Set<String> = buildSet {
        if (card == null) return@buildSet
        fun value(value: Any?) { (value as? String)?.takeIf { it.isNotBlank() }?.let { add(it) } }
        listOf("mBgImage", "mCardArt").forEach { value(cardField(card, it)) }
        listOf("getCardArt", "getImageUrl").forEach { name ->
            attempt("card-url-$name") { value(read(card, name)) }
        }
        val ui = cardField(card, "mCardUIInfo")
        if (ui != null) {
            listOf("mPersonalCardFace", "mCardGifBgHdUrl", "mCardIssuedListBgHdUrl").forEach { value(cardField(ui, it)) }
            attempt("background") { value(read(ui, "getBackground")) }
        }
        if (targets.islandImage.parameterTypes[0].isInstance(card)) {
            attempt("island-original") { value(original(targets.islandImage, null, card)) }
        }
        attempt("product-art") { value(read(read(card, "getProduct"), "getCardArt")) }
    }

    private fun loadCards(context: Context): List<WalletCard> {
        val type = Class.forName("com.miui.tsmclient.entity.CardInfoManager", false, loader)
        val manager = cardMethod(type, "getInstance", Context::class.java).invoke(null, context)
        val records = linkedMapOf<String, WalletCard>()
        // 先取本地已开通卡片；服务器暂不可用时仍可编辑本机卡片。
        attempt("local-cards") {
            (read(manager, "getIssuedCards") as? List<*>)?.filterNotNull()?.forEach { card ->
                val id = aid(card)?.takeIf { it.isNotBlank() } ?: return@forEach
                val title = (cardField(card, "mCardName") as? String).orEmpty().ifBlank { id }
                records[id] = WalletCard(id, title, (cardField(card, "mCardType") as? String).orEmpty(), urls(card))
            }
        }
        try {
            val http = targets.httpClient.apply { isAccessible = true }.invoke(null, context)
            val request = targets.requestClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(true)
            val response = checkNotNull(targets.httpExecute.apply { isAccessible = true }.invoke(http, request))
            val payload = response.javaClass.declaredFields.single { it.type == Any::class.java }
                .apply { isAccessible = true }.get(response)
            val data = read(payload, "getData")
            val cards = checkNotNull(read(data, "getCards") as? List<*>) { "Wallet card response unavailable" }
            cards.filterNotNull().forEach { card ->
                val id = aid(card)?.takeIf { it.isNotBlank() } ?: return@forEach
                val info = cardMethod(type, "getCardInfo", String::class.java).invoke(manager, id)
                val allUrls = records[id]?.urls.orEmpty() + urls(card) + urls(info)
                records[id] = WalletCard(id, (read(card, "getTitle") as? String).orEmpty().ifBlank { id },
                    (read(card, "getCardType") as? String).orEmpty(), allUrls)
            }
        } catch (error: Exception) {
            if (records.isEmpty()) throw error
            report.warn("CARD_LIST_LOCAL_FALLBACK type=${error.javaClass.simpleName}")
        }
        return records.values.toList()
    }

    override fun close() = synchronized(lock) {
        retired = true
        center?.close()
        center = null
    }
}
