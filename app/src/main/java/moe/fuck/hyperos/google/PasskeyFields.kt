package moe.fuck.hyperos.google

import android.annotation.SuppressLint
import android.graphics.Point
import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.Modifier

// 根据 HyperPasskey 的 ART 字段写入方案适配；隐藏 API 通过反射访问，无需打包系统桩类。
internal object PasskeyFields {
    private val type by lazy { Class.forName("sun.misc.Unsafe") }
    private val unsafe by lazy { type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) }
    private fun call(name: String, types: Array<Class<*>>, vararg args: Any?): Any? =
        type.getMethod(name, *types).invoke(unsafe, *args)

    private val intType = Int::class.javaPrimitiveType!!
    private val longType = Long::class.javaPrimitiveType!!
    private fun offset(field: Field) = (call("objectFieldOffset", arrayOf(Field::class.java), field) as Long)
    private fun getInt(base: Any, position: Long) = call("getInt", arrayOf(Any::class.java, longType), base, position) as Int
    private fun putInt(base: Any, position: Long, value: Int) {
        call("putInt", arrayOf(Any::class.java, longType, intType), base, position, value)
    }

    private val fieldOffsetPosition: Long by lazy { findFieldOffsetPosition() }

    // 旧 ART 的可选路径；字段被隐藏或屏蔽时捕获异常并改用下方探针，不能依赖它存在。
    @SuppressLint("SoonBlockedPrivateApi")
    private fun findFieldOffsetPosition(): Long {
        return try {
            offset(Field::class.java.getDeclaredField("offset").apply { isAccessible = true })
        } catch (_: Exception) {
            // 新版 ART 不公开 Field.offset，使用独立探针字段确认偏移后立即复原。
            val probe = Point::class.java.getDeclaredField("x")
            probe.getInt(Point())
            val expected = offset(probe).toInt()
            (8L until 256L step 4).firstOrNull { position ->
                if (getInt(probe, position) != expected) false else {
                    val changed = expected.inv()
                    try {
                        putInt(probe, position, changed)
                        offset(probe).toInt() == changed
                    } finally {
                        putInt(probe, position, expected)
                    }
                }
            } ?: error("ART Field.offset unavailable")
        }
    }

    fun set(field: Field, receiver: Any?, value: Any?) {
        field.isAccessible = true
        val static = Modifier.isStatic(field.modifiers)
        val restricted = Build.VERSION.SDK_INT >= 37 && static && Modifier.isFinal(field.modifiers)
        if (!restricted) {
            try {
                field.set(receiver, value)
                return
            } catch (_: IllegalAccessException) {
                // 回退到 ART 字段偏移写入。
            }
        }
        field.get(receiver)
        val position = getInt(field, fieldOffsetPosition).toLong()
        val base = if (static) field.declaringClass else receiver
        if (field.type == Boolean::class.javaPrimitiveType) {
            call("putBoolean", arrayOf(Any::class.java, longType, Boolean::class.javaPrimitiveType!!), base, position, value)
        } else {
            val name = if (Modifier.isVolatile(field.modifiers)) "putObjectVolatile" else "putObject"
            call(name, arrayOf(Any::class.java, longType, Any::class.java), base, position, value)
        }
    }
}
