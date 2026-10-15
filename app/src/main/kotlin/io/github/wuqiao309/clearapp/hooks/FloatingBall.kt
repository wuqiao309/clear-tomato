package io.github.wuqiao309.clearapp.hooks

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
        probePopupWindows()
        filterPopups()
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
        // 控制器是混淆名（7.3.7 叫 r32.o、7.3.9 叫 y32.o），所以名字找不着时按结构兜底：
        // 「形参是 (ViewGroup, int, Context)、返回 void」这个方法在这场里是唯一的。
        val attach = resolver.findMethodOrByShape(
            LEGACY_PENDANT_CONTROLLER,
            "t",
            listOf("android.view.ViewGroup", "int", "android.content.Context"),
            "void",
            // 这个签名在红果里命中 4 个：框架的 Scene、上面那条 KMP 规则已经管的 PendantController、
            // 一个接口声明，剩下才是它。排除项用可读名，跨版本稳定。
            excluding = listOf(PENDANT),
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

    /**
     * 【临时诊断】那个「商城双11 好物节」弹窗**不走 PopProxy**（日志里一条都没有），所以换个入口找：
     * Dialog / PopupWindow 的 `show()`。Compose 的弹窗底层也是 `Dialog`，所以能覆盖到。
     *
     * 挂上后等它再出现，日志里就有真实类名了。定位完删。
     */
    private fun probePopupWindows() {
        listOf("android.app.Dialog", "android.widget.PopupWindow").forEach { className ->
            val owner = resolver.findClass(className) ?: return@forEach
            // 不限制参数个数：Dialog 是 show()，PopupWindow 是 showAtLocation(...)/showAsDropDown(...)
            owner.declaredMethods
                .filter { it.name.startsWith("show") }
                .forEach { method ->
                    hooks.intercept("probe-window:${className.substringAfterLast('.')}", method) { chain ->
                        log.info("弹窗窗口：${chain.thisObject?.javaClass?.name}")
                        chain.proceed()
                    }
                }
        }
    }

    /**
     * 弹窗拦截。
     *
     * 番茄所有弹窗都走 `PopProxy#popup(Activity, IProperties, ...)`，身份在第二个参数里 ——
     * `PopDefiner.Pop` 那个枚举实现了 `IProperties`，`toString()` 就是弹窗名（共 162 个）。
     *
     * **每个弹窗都记一条日志**（不只是拦掉的）：万一要拦的那个不在名单里，日志里能看到它叫什么。
     */
    private fun filterPopups() {
        val owner = resolver.findClass(POP_PROXY) ?: return
        owner.declaredMethods.filter { it.name == "popup" }.forEach { method ->
            val id = "fanqie-popup-${method.parameterCount}"
            hooks.intercept(id, method) { chain ->
                val name = chain.getArg(1)?.toString().orEmpty()
                if (name in Rules.Fanqie.POPUPS_BLOCKED) {
                    log.info("屏蔽弹窗：$name")
                    null
                } else {
                    log.info("弹窗（放行）：$name")
                    chain.proceed()
                }
            }
        }
    }

    /**
     * 左下角的「继续看剧」悬浮球（KMP 版）。
     *
     * 7.3.7 挂的是 `j(Context)`；7.3.9 把它改成了 `j(int, RecentReadModelKmp, Continuation)`（协程版），
     * 名字对不上就整条失效了。所以三个口都堵：挂载入口、显示入口、以及「正在显示」的查询。
     */
    private fun hideKmpContinueBall() {
        // 挂载：把 ComposeView 塞进 Activity content 的那个
        hooks.intercept("float-kmp-continue-attach", resolver.findMethod(CONTINUE_BALL, "l", "android.content.Context")) { chain ->
            log.info("屏蔽继续看剧球（挂载）")
            null
        }
        // 显示入口
        hooks.intercept(
            "float-kmp-continue-show",
            resolver.findMethod(CONTINUE_BALL, "r", RECENT_READ_MODEL),
        ) { chain ->
            log.info("屏蔽继续看剧球（显示）")
            null
        }
        // 「正在显示吗」—— 别的地方会读它来决定要不要让位
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

        /** 弹窗框架 —— 番茄所有弹窗都从这里走。 */
        const val POP_PROXY = "com.dragon.read.pop.PopProxy"

        /** 继续看球的模型类 —— 在 basenovel 那个 module 里，不在 floatview 包里。 */
        const val RECENT_READ_MODEL = "com.dragon.read.kmp.basenovel.utils.pages.bookmall.data.RecentReadModelKmp"
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
