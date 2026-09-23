package io.github.wuqiao309.clearapp.hooks

import io.github.wuqiao309.clearapp.ClassResolver
import io.github.wuqiao309.clearapp.HookManager
import io.github.wuqiao309.clearapp.ModuleLog
import io.github.wuqiao309.clearapp.readField

/** 书城主 tab 内的子 tab：屏蔽看剧、视频、商城。 */
internal class BookstoreTabs(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    /**
     * 书城的子 tab 列表来自服务端，解析完统一交给 [OWNER] 落到 UI 模型上。
     * 在这里把要屏蔽的项去掉，下游拿到的就是干净的一份。
     */
    fun install() {
        hooks.intercept(ID, resolver.findMethod(OWNER, "a", "int", "java.util.List")) { chain ->
            val tabs = chain.getArg(1) as? List<*> ?: return@intercept chain.proceed()
            val (kept, selected) = dropBlocked(tabs, chain.getArg(0) as Int)
            chain.proceed(arrayOf<Any?>(selected, kept))
        }
    }

    /**
     * 去掉要屏蔽的子 tab，并同步修正被选中的下标。
     *
     * 下标修正必须和被删掉的项在同一轮里算：调用方接下来会拿这个下标去定位当前页，偏一位就会
     * 停在错误的 tab 上。原方法自己删商城时用的也是这套规则。
     */
    private fun dropBlocked(tabs: List<*>, selected: Int): Pair<List<Any?>, Int> {
        val kept = ArrayList<Any?>(tabs.size)
        var newSelected = selected

        tabs.forEachIndexed { index, tab ->
            val name = tab.readField("tabName")?.toString().orEmpty()
            if (name in Rules.Fanqie.BOOKSTORE_TABS_BLOCKED) {
                log.info("屏蔽书城子 tab：$name (type=${tab.readField("tabType")})")
                newSelected = when {
                    index == newSelected -> 0
                    index < newSelected -> newSelected - 1
                    else -> newSelected
                }
            } else {
                kept += tab
            }
        }
        return kept to newSelected
    }

    private companion object {
        const val ID = "fanqie-bookstore-subtab"
        const val OWNER = "com.dragon.read.model.BookMallDefaultTabData"
    }
}
