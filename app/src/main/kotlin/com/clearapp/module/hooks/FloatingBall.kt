package com.clearapp.module.hooks

import com.clearapp.module.ClassResolver
import com.clearapp.module.HookManager
import com.clearapp.module.ModuleLog

/**
 * 悬浮球 / 悬浮窗。
 *
 * 番茄有好几套互不相干的悬浮球（继续看的、活动引流的、发帖的），各自由自己的开关决定要不要出现，
 * 所以按功能逐个关，而不是去拦 View 的创建 —— 后者会把正常界面一起误伤。
 *
 * 唯一没动的是听书的「边听边读」功能球（`ap3.w1`）：它是跳回阅读器继续听看的功能入口，不是引流
 * 组件，按需求保留。
 */
internal class FloatingBall(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    fun install() {
        hideKmpContinueBall()
        hideMainPageFloatWindows()
        hideGrowthFloatingBars()
        hidePendant()
        hideLegacyPendant()
        hideEditorBall()
    }

    /**
     * 老式挂件（`com.bytedance.ug.sdk.novel.pendant`，红果首页右侧那个竖排「一键领」）。
     *
     * 这套和上面 KMP 那套是两个独立的实现、各有各的控制器，所以得单独挂。全工程只有红果带它，
     * 番茄上取不到目标会自己跳过。
     */
    private fun hideLegacyPendant() {
        val attach = resolver.findMethod(
            LEGACY_PENDANT,
            "t",
            "android.view.ViewGroup", "int", "android.content.Context",
        )
        hooks.intercept("float-pendant-legacy-attach", attach) { chain ->
            log.info("屏蔽一键领挂件")
            null
        }
    }

    /**
     * 信息流右下角的发帖悬浮球（一个没文字的圆按钮）。
     *
     * `g` 是这个球唯一的显隐开关（里面做 alpha 动画再设可见性），`show()`/`hide()` 最终都落到它，
     * 但显示它的路径不止 `show()` 一条 —— 所以挂这里而不是挂上层那几个入口。
     * 把入参改成 false，等于「不管谁想显示都当没说过」。
     */
    private fun hideEditorBall() {
        val toggle = resolver.findMethod(EDITOR_BALL, "g", "boolean")
        hooks.intercept("float-editor-ball", toggle) { chain ->
            log.info("屏蔽发帖悬浮球")
            chain.proceed(arrayOf<Any?>(false))
        }
    }

    /**
     * 金币/福利挂件（UG SDK 的 pendant），常年漂在页面右下角。
     *
     * 挂载方法是它唯一的入口：宿主 ViewGroup 和 Compose 承载视图都在里面创建，不进去就什么都不会贴
     * 到界面上。方法名两个 app 不一样（番茄 `s`、红果 `t`），所以按参数表找。
     */
    private fun hidePendant() {
        val attach = resolver.findMethodAnyName(
            PENDANT,
            listOf("s", "t"),
            "android.view.ViewGroup", "int", "android.content.Context",
        )
        hooks.intercept("float-pendant-attach", attach) { chain ->
            log.info("屏蔽金币挂件")
            null
        }
    }

    /** 继续看悬浮球（KMP 版，会把自己塞进 Activity 的 content 里）。 */
    private fun hideKmpContinueBall() {
        hooks.intercept("float-kmp-continue-attach", resolver.findMethod(CONTINUE_BALL, "j", "android.content.Context")) { chain ->
            log.info("屏蔽继续看悬浮球")
            null
        }
        hooks.replaceBoolean("float-kmp-continue-showing", resolver.findMethod(CONTINUE_BALL, "o"), false)
    }

    /** 主页面上的「继续看」悬浮窗（旧实现）。 */
    private fun hideMainPageFloatWindows() {
        hooks.intercept("float-main-page-show", resolver.findMethod(MAIN_HELPER, "h", "android.app.Activity")) { chain ->
            log.info("屏蔽主页面悬浮窗")
            null
        }
    }

    /** 冷启后的增长类悬浮条（提现、漫画引导、节日引导）。 */
    private fun hideGrowthFloatingBars() {
        GROWTH_ENTRIES.forEach { entry ->
            hooks.intercept("float-growth-$entry", resolver.findMethod(GROWTH, entry)) { chain ->
                log.info("屏蔽增长悬浮条：$entry")
                null
            }
        }
    }

    private companion object {
        const val CONTINUE_BALL = "com.dragon.read.kmp.bookmall.floatview.FloatViewShowManager"
        const val MAIN_HELPER = "com.dragon.read.pages.main.z"
        const val GROWTH = "com.dragon.read.polaris.cold.start.l"
        const val PENDANT = "com.bytedance.ug.sdk.kmp.novel.pendant.manager.PendantController"
        const val LEGACY_PENDANT = "r32.o"
        const val EDITOR_BALL = "ma5.o"

        val GROWTH_ENTRIES = listOf(
            "m",
            "tryShowLowTakeCashFloatingView",
            "tryShowComic7DayFloatingView",
            "tryShowFestivalComicGuideFloatingView",
        )
    }
}
