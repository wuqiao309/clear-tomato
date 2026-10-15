package io.github.wuqiao309.clearapp.hooks

/**
 * 各 app 的屏蔽策略。
 *
 * 番茄和红果出自同一套代码：类名、方法名、甚至 `BottomTabBarItemType` 这个枚举都一模一样，所以
 * **机制**可以共用。但**策略**必须分开 —— 同一个枚举值在两个 app 里指的是不同的 tab：
 *
 * | 枚举 | 番茄 | 红果 |
 * | --- | --- | --- |
 * | `BookStore` | 书城 | 剧场 |
 * | `VideoSeriesFeedTab` | 短剧 | 首页 |
 * | `BookShelf` | 书架 | （不显示） |
 * | `LuckyBenefit` | 赚钱 | 赚钱 / 福利 |
 * | `ShopMall` | 商城 | 商城 |
 * | `MyProfile` | 我的 | 我的 |
 */
internal object Rules {

    /** 番茄免费小说 (`com.dragon.read`)。 */
    object Fanqie {

        /** 底部主 tab 白名单：只留书城、书架、我的。 */
        val BOTTOM_TABS_KEPT = setOf("BookStore", "BookShelf", "MyProfile")

        /** 书城主 tab 内要屏蔽的子 tab（服务端下发的文案）。 */
        val BOOKSTORE_TABS_BLOCKED = setOf("看剧", "视频", "商城")

        /** 书架主 tab 内要屏蔽的子 tab（服务端下发的文案）。 */
        val BOOKSHELF_TABS_BLOCKED = setOf("收藏")

        /**
         * 「我的」页上要整张屏蔽的卡片，取值见 `CardType`。
         *
         * 金币、现金余额、微信提现、福利是同一张 Polaris 卡里的几行，整张去掉最干净。
         */
        val MINE_CARDS_BLOCKED = setOf("POLARIS")

        /** 「我的」页金币/提现相关的入口，取值见 [MyTabCell]。 */
        val MINE_CELLS_BLOCKED = setOf(
            MyTabCell.GOLD_BALANCE,
            MyTabCell.TT_NOVEL_COIN,
            MyTabCell.DOUYIN_WALLET,
            MyTabCell.HONG_GUO_COIN,
        )

        /**
         * 要拦掉的弹窗，名字取自 `PopDefiner.Pop` 枚举（共 162 个）。
         *
         * 这是**导流到商城/带货**的那一族：营销弹窗、商城广告、优惠券催促。
         * 日志里每个弹窗都会打名字（放行的也打），所以拦错了或者漏了，照日志里的名字改这份名单即可。
         */
        val POPUPS_BLOCKED = setOf(
            "tt_ecom_marketing_popup",      // 电商营销弹窗 —— 最像「跳商场」的那个
            "bookmall_ecom_popup",          // 商城弹窗
            "bookmall_ad_popup",            // 商城广告弹窗
            "bookmall_ecom_coupon_urge",    // 催领商城优惠券
            "bookmall_ecom_qcpx_coupon",    // 商城券
            "bookmall_caijing_popup",       // 财经弹窗
            "ecommerce_coupon_dialog",      // 电商优惠券
        )
    }

    /** 红果免费短剧 (`com.phoenix.read`)。 */
    object Hongguo {

        /** 底部主 tab 白名单：只留首页、剧场、我的。 */
        val BOTTOM_TABS_KEPT = setOf("VideoSeriesFeedTab", "BookStore", "MyProfile")
    }

    /** `com.dragon.read.rpc.model.MyTabCellType` 里与金币/提现相关的几项。 */
    private object MyTabCell {
        /** 金币余额。 */
        const val GOLD_BALANCE = 2

        /** 番茄金币入口。 */
        const val TT_NOVEL_COIN = 119

        /** 微信提现。 */
        const val DOUYIN_WALLET = 124

        /** 红果金币。 */
        const val HONG_GUO_COIN = 134
    }
}
