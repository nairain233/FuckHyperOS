package moe.fuck.hyperos.hyperos

import java.io.File

internal object LargeIconRights {
    fun ensure(directory: File, productId: String) {
        require(productId.isNotBlank() && productId != "." && productId != ".." &&
            productId.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid product ID" }
        val root = directory.canonicalFile
        check(root.isDirectory || root.mkdirs()) { "Cannot create rights directory" }
        val file = File(root, "$productId-largeicons.mra").canonicalFile
        check(file.parentFile == root) { "Rights path escapes directory" }
        check(file.isFile || file.createNewFile()) { "Cannot create rights file" }
    }
}
