package com.clearapp.module.hooks

import com.clearapp.module.ClassResolver
import com.clearapp.module.HookManager
import com.clearapp.module.ModuleLog
import com.clearapp.module.readField

/** 书架主 tab 内的子 tab：屏蔽收藏。 */
internal class BookshelfTabs(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    fun install() {
        hooks.intercept(ID, resolver.findMethod(FRAGMENT, "ch", "boolean")) { chain ->
            dropBlockedTabs()
            chain.proceed()
        }
    }

    /**
     * 书架子 tab 的清单缓存在 [REGISTRY] 的静态字段上，重建 tab 时会被整份读出来。
     * 在它被读之前剪掉要屏蔽的项，tab 就不会被建出来。
     *
     * 每次刷新都会走一遍，重复删除是幂等的。
     */
    private fun dropBlockedTabs() {
        val tabs = resolver.staticField(REGISTRY, TAB_LIST_FIELD) as? MutableList<*>
        if (tabs == null) {
            log.warn("书架 tab 过滤跳过：拿不到 $REGISTRY.$TAB_LIST_FIELD")
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
        const val REGISTRY = "t76.i"
        const val TAB_LIST_FIELD = "d"
    }
}
