package com.clearapp.module.hooks

import com.clearapp.module.ClassResolver
import com.clearapp.module.HookManager
import com.clearapp.module.ModuleLog

/**
 * 红果免费短剧 (`com.phoenix.read`) 的界面精简规则。
 *
 * 红果和番茄共用同一套类，所以机制部分（底部 tab、悬浮球）直接复用；红果没有番茄的书城/书架子 tab
 * 和「我的」页那套卡片，那几组规则不装。
 */
class HongguoHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    fun installAll() {
        BottomTabs(hooks, resolver, log, Rules.Hongguo.BOTTOM_TABS_KEPT).install()
        FloatingBall(hooks, resolver, log).install()
    }
}
