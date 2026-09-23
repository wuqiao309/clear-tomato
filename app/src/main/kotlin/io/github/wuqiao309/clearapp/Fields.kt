package io.github.wuqiao309.clearapp

/**
 * 读宿主对象上的字段值，读不到返回 null。
 *
 * 屏蔽规则要按服务端下发的文案判断该不该拦（例如子 tab 叫不叫「收藏」），而这些模型类的字段名
 * 来自反编译结果。某个版本把字段改了名，只会让用到它的那一条规则失效并留下日志，不会连带别的规则。
 */
internal fun Any?.readField(name: String): Any? {
    val target = this ?: return null
    return runCatching { target.javaClass.getField(name).get(target) }
        .getOrElse {
            runCatching {
                target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)
            }.getOrNull()
        }
}
