package com.clearapp.module.hooks

import com.clearapp.module.ClassResolver
import com.clearapp.module.HookManager
import com.clearapp.module.ModuleLog

/**
 * 底部主 tab。
 *
 * [keptTypes] 由调用方按 app 给 —— 同一个 `BottomTabBarItemType` 在两个 app 里指的是不同的 tab，
 * 详见 [Rules]。
 */
internal class BottomTabs(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
    private val keptTypes: Set<String>,
) {

    fun install() {
        filterTabCreation()
        probeTabBar()
    }

    /**
     * 底栏上的每个 tab 都先经这里建出来，建不出来自然就不会出现在底栏。
     *
     * 让这个方法返回 null 是它本来就有的契约：商城在插件没装、福利在 Polaris 关掉时都是这么返回的，
     * 所以调用方已经能接住 null。
     */
    private fun filterTabCreation() {
        hooks.intercept(
            ID,
            resolver.findMethod(TAB_FACTORY, "c", "android.view.ViewGroup", TAB_TYPE),
        ) { chain ->
            val type = (chain.getArg(1) as? Enum<*>)?.name
            when {
                type == null -> chain.proceed()
                type in keptTypes -> {
                    log.info("保留底部 tab：$type")
                    chain.proceed()
                }
                else -> {
                    log.info("屏蔽底部 tab：$type")
                    null
                }
            }
        }
    }

    /**
     * 探针，只记日志 —— 用来把「`BottomTabBarItemType` 的哪个值对应界面上哪个 tab」打出来。
     *
     * 两个 app 的枚举值含义不一样（番茄的 `BookStore` 是书城、红果的是剧场），又都是服务端下发的
     * 文案，光看代码定不下来。app 升级后改 [Rules] 前先看这几行日志。
     */
    private fun probeTabBar() {
        hooks.probe("bottom-tab-add", resolver.findMethod(TAB_BAR, "P1", "ig7.g"))
        hooks.probe("bottom-tab-add-hg", resolver.findMethod(TAB_BAR, "R1", "ve7.g"))
        TAB_BUTTONS.forEach { name ->
            hooks.intercept("bottom-tab-label:$name", resolver.findMethod(name, "setText", "String")) { chain ->
                val type = chain.thisObject?.let { button ->
                    runCatching { button.javaClass.getMethod("a").invoke(button) }.getOrNull()
                }
                log.info("底部 tab：$type = ${chain.getArg(0)}")
                chain.proceed()
            }
        }
    }

    private companion object {
        const val ID = "bottom-tab"

        const val TAB_FACTORY = "com.dragon.read.pages.main.m"
        const val TAB_TYPE = "com.dragon.read.rpc.model.BottomTabBarItemType"
        const val TAB_BAR = "com.dragon.read.widget.BottomTabBarLayout"

        /** tab 按钮实现的混淆名两个 app 不一样（番茄 `ig7.j`、红果 `ve7.j`），逐个试。 */
        val TAB_BUTTONS = listOf("ig7.j", "ve7.j")
    }
}
