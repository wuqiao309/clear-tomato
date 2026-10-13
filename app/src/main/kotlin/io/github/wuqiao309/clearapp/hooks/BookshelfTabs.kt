package io.github.wuqiao309.clearapp.hooks

import io.github.wuqiao309.clearapp.ClassResolver
import io.github.wuqiao309.clearapp.HookManager
import io.github.wuqiao309.clearapp.ModuleLog
import io.github.wuqiao309.clearapp.readField

/** 书架主 tab 内的子 tab：屏蔽收藏。 */
internal class BookshelfTabs(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    fun install() {
        val rebuild = resolver.findMethodAnyName(FRAGMENT, listOf("dh", "ch"), "boolean")
        hooks.intercept(ID, rebuild) { chain ->
            dropBlockedTabs()
            chain.proceed()
        }
    }

    /**
     * 书架子 tab 的清单缓存在 [REGISTRY_CANDIDATES] 命中的那个类的静态字段上，重建 tab 时会被整份读出来。
     * 在它被读之前剪掉要屏蔽的项，tab 就不会被建出来。
     *
     * 每次刷新都会走一遍，重复删除是幂等的。
     */
    private fun dropBlockedTabs() {
        val tabs = REGISTRY_CANDIDATES
            .firstNotNullOfOrNull { resolver.staticField(it, TAB_LIST_FIELD) as? MutableList<*> }
        if (tabs == null) {
            log.warn("书架 tab 过滤跳过：$REGISTRY_CANDIDATES 上都拿不到 $TAB_LIST_FIELD")
            return
        }

        val iterator = tabs.iterator()
        while (iterator.hasNext()) {
            val name = iterator.next().readField("name")?.toString()
            if (name in Rules.Fanqie.BOOKSHELF_TABS_BLOCKED) {
                iterator.remove()
                log.info("屏蔽书架子 tab：$name")
            }
        }
    }

    private companion object {
        const val ID = "fanqie-bookshelf-subtab"
        const val FRAGMENT = "com.dragon.read.component.biz.impl.bookshelf.tab.MultiTabShelfFragment"
        /** 子 tab 注册表。类名随版本变：7.3.7 是 `t76.i`、7.3.9 是 `hb6.i`。 */
        val REGISTRY_CANDIDATES = listOf("hb6.i", "t76.i")
        const val TAB_LIST_FIELD = "d"
    }
}
