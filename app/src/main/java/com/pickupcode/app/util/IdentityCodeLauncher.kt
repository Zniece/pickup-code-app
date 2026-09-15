package com.pickupcode.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 跳转到「身份码」页面。
 *
 * 三条深链都是**真机实测验证过**的（详见 `.scratch/identity-code-deeplink.md` §7），
 * 不是网上抄的 scheme：
 *  - 淘宝：`taobao://m.taobao.com/tbopen/index.html?h5Url=<目标H5>` —— 淘宝的**通用 H5 网关**，
 *    目标 H5 = `.../last-mile-fe/end-collect-platform/identity-code`
 *  - 菜鸟：`cainiao://router/station_code` —— 菜鸟内部 `guoguo://go/<route>` 对外就是 `router/<route>`
 *  - 拼多多：`pinduoduo://com.xunmeng.pinduoduo/mdkd/package`
 *
 * 关键经验（踩过的坑都写在这）：
 *  1. **必须 setPackage**：只给 scheme 让系统挑，会挑到未导出的内部 Activity（抛 SecurityException），
 *     或被别的 App 抢走（实测被 Via 浏览器接管）。
 *  2. 也不能只 setComponent 打内部页面：那些 Activity **未导出**（`not exported from uid …`）。
 *     真正可行的是"打导出的入口 + 数据走 scheme"。
 *  3. 国内 ROM 会拦截跨应用跳转（vivo 实测弹 `com.vivo.appfilter/AppJumpPromptActivity` 要用户确认一次），
 *     所以每一步都要有失败反馈，最后能退到"打开 App 首页"，再不行就提示手动。
 *
 * ⚠️ 隐私红线：身份码是**取件凭证**。本模块只负责"把用户送过去"，**不读取、不截图、不缓存**任何身份码内容。
 */
object IdentityCodeLauncher {

    private const val TAG = "IdentityCodeLauncher"

    const val TAOBAO = "com.taobao.taobao"
    const val CAINIAO = "com.cainiao.wireless"
    const val PINDUODUO = "com.xunmeng.pinduoduo"

    /** 淘宝身份码 H5（真机日志实测：淘宝加载的正是这个地址）。 */
    private const val TAOBAO_IDENTITY_H5 =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code"

    /** 菜鸟导出入口（第三方路由器），内部派发到 `.identity_code.IdentityCodeActivity`。 */
    private const val CAINIAO_ROUTE = "cainiao://router/station_code"

    /** 拼多多快递包裹页（mdkd）——身份码入口在该页内。 */
    private const val PINDUODUO_ROUTE = "pinduoduo://com.xunmeng.pinduoduo/mdkd/package"

    /** 跳转结果：调用方据此给用户明确反馈（不允许静默失败）。 */
    sealed interface Result {
        /** 已成功把用户送过去。 */
        data object Opened : Result
        /** 目标 App 没装。 */
        data object NotInstalled : Result
        /** 装了但跳转失败（系统拦截/对方改版），已尝试打开 App 首页。 */
        data class Failed(val message: String) : Result
    }

    fun openTaobao(context: Context): Result {
        val uri = Uri.parse("taobao://m.taobao.com/tbopen/index.html?h5Url=$TAOBAO_IDENTITY_H5")
        val intent = baseIntent(uri).setPackage(TAOBAO)
        // 备选：显式打导出入口 Welcome（真机实测取的正是它）。内部页面（如 cainiao 卡片页）未导出，不要用。
        val explicit = Intent(Intent.ACTION_VIEW, uri)
            .setComponent(ComponentName(TAOBAO, "com.taobao.tao.welcome.Welcome"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(context, TAOBAO, "淘宝", intent, explicit)
    }

    fun openCainiao(context: Context): Result {
        val uri = Uri.parse(CAINIAO_ROUTE)
        return launch(context, CAINIAO, "菜鸟", baseIntent(uri).setPackage(CAINIAO))
    }

    fun openPinduoduo(context: Context): Result {
        val uri = Uri.parse(PINDUODUO_ROUTE)
        return launch(context, PINDUODUO, "拼多多", baseIntent(uri).setPackage(PINDUODUO))
    }

    /** 目标 App 是否已安装（依赖 AndroidManifest 里的 `<queries>`）。 */
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 把 [Result] 翻译成给用户看的一句话；成功返回 null（无需提示）。
     * 统一在这里维护，避免各入口（身份码页 / 详情页标题栏）各写一套文案。
     */
    fun resultMessage(result: Result): String? = when (result) {
        Result.Opened -> null
        Result.NotInstalled -> "未安装该应用"
        is Result.Failed -> result.message
    }

    private fun baseIntent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 三级降级：主深链 → 备选深链（显式入口）→ App LAUNCHER 首页。
     * 全失败才返回 [Result.Failed]，保证调用方能给用户可操作的信息。
     */
    private fun launch(
        context: Context,
        pkg: String,
        appLabel: String,
        vararg intents: Intent
    ): Result {
        if (!isInstalled(context, pkg)) return Result.NotInstalled

        val errors = mutableListOf<String>()
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return Result.Opened
            } catch (e: Exception) {
                errors += e.javaClass.simpleName
                Log.w(TAG, "$appLabel 深链失败（${e.javaClass.simpleName}），尝试下一级", e)
            }
        }

        // 最后兜底：打开 App 首页（用户自己点进身份码）
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return Result.Failed("未能直达身份码，已打开$appLabel 首页；请点「身份码」入口")
            }
        } catch (e: Exception) {
            errors += e.javaClass.simpleName
        }
        return Result.Failed("打开$appLabel 失败（${errors.distinct().joinToString("/")}）")
    }
}
