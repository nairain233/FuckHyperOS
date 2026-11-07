package moe.fuck.hyperos.card

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class CardStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("xiaoai_card_bindings", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "xiaoai_card_images").apply {
        check(isDirectory || mkdirs()) { "无法创建卡面目录" }
    }
    @Volatile private var bindings = readBindings()

    private fun readBindings(): List<CardBinding> = try {
        val array = JSONArray(prefs.getString("bindings", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val urls = item.getJSONArray("urls")
            CardBinding(item.getString("aid"), item.getString("image"),
                (0 until urls.length()).map { urls.getString(it) }.toSet())
        }.filter { validCardImageName(it.image) }
    } catch (_: Exception) { emptyList() }

    private fun save(updated: List<CardBinding>) {
        val json = JSONArray().apply {
            updated.forEach { binding -> put(JSONObject().apply {
                put("aid", binding.aid)
                put("image", binding.image)
                put("urls", JSONArray(binding.urls.toList()))
            }) }
        }
        check(prefs.edit().putString("bindings", json.toString()).commit()) { "保存卡面设置失败" }
        bindings = updated
    }

    fun imageFile(name: String): File {
        require(validCardImageName(name)) { "卡面文件名无效" }
        return File(directory, name)
    }

    fun replacement(aid: String?, url: String?): String? =
        selectCardImage(aid, url, bindings)?.let { name ->
            imageFile(name).takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it).toString() }
        }

    fun localFile(url: String?): File? {
        if (url == null) return null
        val uri = Uri.parse(url)
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        if (!validCardImageName(file.name) || file.parentFile?.canonicalFile != directory.canonicalFile) return null
        return file.takeIf { it.isFile }
    }

    @Synchronized fun bind(card: WalletCard, image: String) {
        require(imageFile(image).isFile) { "卡面图片不存在，请重新导入" }
        save(bindings.filterNot { it.aid == card.aid } + CardBinding(card.aid, image, card.urls))
    }

    @Synchronized fun reset(aid: String) { save(bindings.filterNot { it.aid == aid }) }

    @Synchronized fun delete(name: String) {
        val file = imageFile(name)
        save(bindings.filterNot { it.image == name })
        check(!file.exists() || file.delete()) { "卡面图片删除失败" }
    }

    // 先写临时文件并验证，再发布唯一文件名；不会覆盖现有卡面和缓存键。
    @Synchronized fun importImage(descriptor: ParcelFileDescriptor): String {
        val name = UUID.randomUUID().toString().replace("-", "") + ".png"
        val temporary = File(directory, "$name.tmp")
        val normalized = File(directory, "$name.ready")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                temporary.outputStream().use { copyCardImage(input, it) }
            }
            val bitmap = CardImages.decode(temporary)
            try {
                normalized.outputStream().use { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "图片保存失败" }
                }
            } finally { bitmap.recycle() }
            check(normalized.renameTo(imageFile(name))) { "图片导入失败" }
            return name
        } finally {
            temporary.delete()
            normalized.delete()
        }
    }

    fun snapshot(cards: List<WalletCard>): Bundle = Bundle().apply {
        putParcelableArrayList("cards", ArrayList(cards.take(200).map { card -> card.bundle().apply {
            putString("image", bindings.singleOrNull { it.aid == card.aid }?.image)
        } }))
        putStringArrayList("images", ArrayList(directory.listFiles().orEmpty()
            .filter { validCardImageName(it.name) && it.isFile }.map { it.name }.sorted().take(300)))
    }
}
