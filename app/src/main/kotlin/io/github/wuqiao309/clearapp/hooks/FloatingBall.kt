package io.github.wuqiao309.clearapp.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.wuqiao309.clearapp.ClassResolver
import io.github.wuqiao309.clearapp.HookManager
import io.github.wuqiao309.clearapp.ModuleLog

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
     * 这套和上面 KMP 那套是两个独立的实现，得单独挂。全工程只有红果带它，番茄上取不到目标会跳过。
     *
     * 挂法绕了一层：球本身那个类（7.3.7 叫 `v32.e`、7.3.9 叫 `c42.h0`）包号随版本变，但**建它的服务**
     * 名字是可读的、稳定的。所以从服务的 `getPendantFloatTips` 拿回球对象，再对它那个**实际类**挂
     * 显隐方法 —— 升级时不用重新找类名。
     */
    private fun hideLegacyPendant() {
        val attach = resolver.findMethod(
            LEGACY_PENDANT_CONTROLLER,
            "t",
            "android.view.ViewGroup", "int", "android.content.Context",
        )
        hooks.intercept("float-pendant-legacy-attach", attach) { chain ->
            log.info("屏蔽一键领挂件（挂载）")
            null
        }

        val show = resolver.findMethodByArity(LEGACY_PENDANT_SERVICE, "tryShowFloatTips", 15)
        hooks.intercept("float-pendant-legacy-show", show) { chain ->
            log.info("屏蔽一键领挂件（入口）")
            null
        }
    }

    /**
     * 信息流右下角的发帖悬浮球（一个没文字的圆按钮）。
     *
     * 挂的是球自己的显隐开关（内部把 alpha 从 0 动到 1 或反过来）。上层的 `show()`/`hide()` 最终都
     * 落到它，但显示它的路径不止 `show()` 一条 —— 所以挂这里而不是挂入口。把入参改成 false，等于
     * 「不管谁想显示都当没说过」。
     */
    private fun hideEditorBall() {
        val toggle = resolver.findMethod(EDITOR_BALL, "i", "boolean")
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

    /**
     * 【临时诊断】主界面起来后把视图树里**落在右侧中间那块**的节点打出来，用来认那个球是什么类。
     * 定位完就删。
     */
    private fun probeViewTree() {
        val onResume = resolver.findMethodByArity("android.app.Activity", "onResume", 0) ?: return
        hooks.intercept("probe-viewtree", onResume) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? Activity)?.let { activity ->
                Handler(Looper.getMainLooper()).postDelayed({
                    val root = activity.window?.decorView ?: return@postDelayed
                    log.info("视图树（右侧中部）：\n${ballRegionNodes(root).joinToString("\n")}")
                }, 10_000)
            }
            result
        }
    }

    private fun ballRegionNodes(root: View): List<String> {
        val found = mutableListOf<String>()
        val location = IntArray(2)

        fun walk(view: View, depth: Int) {
            if (depth > 30) return
            view.getLocationOnScreen(location)
            val (x, y) = location[0] to location[1]
            if (x >= 1000 && y in 900..1500) {
                found += "  ".repeat(depth) + "${view.javaClass.name}  @[$x,$y] ${view.width}x${view.height}"
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }

        walk(root, 0)
        return found
    }

    private companion object {
        const val CONTINUE_BALL = "com.dragon.read.kmp.bookmall.floatview.FloatViewShowManager"
        const val MAIN_HELPER = "com.dragon.read.pages.main.z"
        const val GROWTH = "com.dragon.read.polaris.cold.start.l"
        const val PENDANT = "com.bytedance.ug.sdk.kmp.novel.pendant.manager.PendantController"
        const val LEGACY_PENDANT_SERVICE = "com.bytedance.ug.sdk.novel.pendant.PendantFloatTipsServiceImpl"

        /** 老式挂件的控制器（7.3.7 叫 `r32.o`、7.3.9 叫 `y32.o`），球在它的挂载方法里贴到界面上。 */
        const val LEGACY_PENDANT_CONTROLLER = "y32.o"

        /** 发帖球。7.3.7 是 `ma5.o`、7.3.9 是 `dd5.o`（混淆包号每次升级都会变）。 */
        const val EDITOR_BALL = "dd5.o"

        val GROWTH_ENTRIES = listOf(
            "m",
            "tryShowLowTakeCashFloatingView",
            "tryShowComic7DayFloatingView",
            "tryShowFestivalComicGuideFloatingView",
        )
    }
}
