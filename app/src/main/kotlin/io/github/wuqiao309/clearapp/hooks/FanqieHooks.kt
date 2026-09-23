package io.github.wuqiao309.clearapp.hooks

import io.github.wuqiao309.clearapp.ClassResolver
import io.github.wuqiao309.clearapp.HookManager
import io.github.wuqiao309.clearapp.ModuleLog

/**
 * 番茄免费小说 (`com.dragon.read`) 的界面精简规则。
 *
 * 按界面上要清理的东西分组，每组一个类、各自独立安装：某一组的目标在这个版本里改了签名，只会让
 * 那一组挂不上并留下日志，不会连带其它组。
 */
class FanqieHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    fun installAll() {
        BottomTabs(hooks, resolver, log, Rules.Fanqie.BOTTOM_TABS_KEPT).install()
        BookstoreTabs(hooks, resolver, log).install()
        BookshelfTabs(hooks, resolver, log).install()
        ProfilePage(hooks, resolver, log).install()
        FloatingBall(hooks, resolver, log).install()
    }
}
