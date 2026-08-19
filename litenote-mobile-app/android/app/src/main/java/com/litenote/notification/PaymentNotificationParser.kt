package com.litenote.notification

import java.util.Locale

/**
 * 从通知中解析可自动入账的支出。
 *
 * 识别策略采用 fail-closed：必须同时满足可信来源、明确支出语义和有效金额，
 * 普通聊天里单独出现“支付”“100 元”等字样不会触发。
 */
object PaymentNotificationParser {

    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    const val PINDUODUO_PACKAGE = "com.xunmeng.pinduoduo"
    const val ICBC_PACKAGE = "com.icbc"

    val SMS_PACKAGES = setOf(
        "com.hihonor.mms",
        "com.android.mms",
        "com.google.android.apps.messaging"
    )

    val BANK_APP_PACKAGES = setOf(ICBC_PACKAGE)

    val DEFAULT_SUPPORTED_PACKAGES = setOf(
        WECHAT_PACKAGE,
        ALIPAY_PACKAGE
    )

    val KNOWN_SUPPORTED_PACKAGES = setOf(
        WECHAT_PACKAGE,
        ALIPAY_PACKAGE,
        PINDUODUO_PACKAGE
    ) + SMS_PACKAGES + BANK_APP_PACKAGES

    private val incomeOrReversalKeywords = listOf(
        "收款", "到账", "入账", "收入", "转入", "存入", "退款", "退货", "撤销", "冲正", "红包"
    )

    private val amountPatterns = listOf(
        Regex("""(?:人民币|RMB|CNY|¥|￥)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元"""),
        Regex(
            """(?:支付成功|付款成功|成功付款|成功支付|已支付|已付款|扣款成功|扣费成功|已扣款|已扣费|实付)\s*[：:]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
        )
    )

    private val categoryRules = linkedMapOf(
        "餐饮" to listOf("餐饮", "外卖", "饿了么", "美团外卖", "饭店", "餐厅", "咖啡", "奶茶", "肯德基", "麦当劳"),
        "交通" to listOf("滴滴", "打车", "出行", "地铁", "公交", "加油", "停车", "铁路", "高铁", "航空", "机票"),
        "购物" to listOf("拼多多", "淘宝", "天猫", "京东", "商城", "购物", "商店", "超市", "快递"),
        "居住" to listOf("房租", "住房", "物业", "水费", "电费", "燃气", "宽带"),
        "住房" to listOf("房租", "住房", "物业", "水费", "电费", "燃气", "宽带"),
        "娱乐" to listOf("游戏", "电影", "影院", "视频会员", "音乐会员", "KTV", "演出"),
        "医疗" to listOf("医院", "药店", "医疗", "挂号", "诊所", "体检"),
        "教育" to listOf("学费", "培训", "课程", "教育", "书店", "考试")
    )

    fun parse(data: PaymentNotificationContent, customKeywords: List<String> = emptyList()): PaymentMatch? {
        val content = data.allContent()
        if (content.isBlank() || incomeOrReversalKeywords.any(content::contains)) {
            return null
        }

        val amount = extractAmount(content) ?: return null
        if (amount <= 0.0) {
            return null
        }

        val source = when (data.packageName) {
            WECHAT_PACKAGE -> parseWechat(data, content, customKeywords)
            ALIPAY_PACKAGE -> parseAlipay(data, content, customKeywords)
            PINDUODUO_PACKAGE -> parsePinduoduo(content)
            in SMS_PACKAGES -> parseBankSms(data, content, customKeywords)
            in BANK_APP_PACKAGES -> parseBankApp(data, content, customKeywords)
            else -> null
        } ?: return null

        val categoryHint = if (data.packageName == PINDUODUO_PACKAGE) {
            "购物"
        } else {
            inferCategory(content)
        }
        val paymentChannel = inferPaymentChannel(source, content)
        val counterparty = extractCounterparty(data, source)

        return PaymentMatch(
            amount = amount,
            source = source,
            categoryHint = categoryHint,
            description = counterparty ?: "自动记账 · $paymentChannel",
            paymentChannel = paymentChannel,
            counterparty = counterparty
        )
    }

    private fun parseWechat(
        data: PaymentNotificationContent,
        content: String,
        customKeywords: List<String>
    ): String? {
        val officialIdentity = listOf(data.title, data.titleBig, data.subText).any {
            it.contains("微信支付") || it.contains("支付助手")
        } || content.contains("微信支付凭证")

        val strongAction = containsAny(
            content,
            listOf("支付成功", "付款成功", "扣款成功", "扣费成功", "交易成功", "已支付", "已扣费") +
                sanitizeKeywords(customKeywords)
        )
        return if (officialIdentity && strongAction) "wechat" else null
    }

    private fun parseAlipay(
        data: PaymentNotificationContent,
        content: String,
        customKeywords: List<String>
    ): String? {
        val officialIdentity = listOf(data.title, data.titleBig, data.subText).any {
            containsAny(it, listOf("支付宝", "交易提醒", "支付助手", "账单"))
        } || content.contains("支付宝")

        val strongAction = containsAny(
            content,
            listOf(
                "支付成功", "付款成功", "成功付款", "成功支付", "已支付", "已付款",
                "扣款成功", "扣费成功", "已扣款", "已扣费", "交易成功", "支出", "消费"
            ) + sanitizeKeywords(customKeywords)
        )
        return if (officialIdentity && strongAction) "alipay" else null
    }

    private fun parsePinduoduo(content: String): String? {
        val strongAction = containsAny(
            content,
            listOf("支付成功", "付款成功", "订单已支付", "成功支付", "扣款成功")
        )
        return if (strongAction) "pinduoduo" else null
    }

    private fun parseBankSms(
        data: PaymentNotificationContent,
        content: String,
        customKeywords: List<String>
    ): String? {
        val title = data.title.trim()
        val bankIdentity = containsAny(
            content,
            listOf("银行", "信用卡", "储蓄卡", "借记卡", "尾号", "账户", "卡号")
        ) || Regex("""^9[0-9]{4,5}$""").matches(title)

        val outgoingAction = containsAny(
            content,
            listOf("消费", "支出", "扣款", "支付", "转出", "快捷支付", "交易金额") + sanitizeKeywords(customKeywords)
        )
        return if (bankIdentity && outgoingAction) "bank_sms" else null
    }

    private fun parseBankApp(
        data: PaymentNotificationContent,
        content: String,
        customKeywords: List<String>
    ): String? {
        val bankIdentity = listOf(data.title, data.titleBig, data.subText).any {
            containsAny(it, listOf("工商银行", "动账通知", "交易提醒"))
        } || containsAny(content, listOf("工商银行", "尾号", "动账通知"))

        val outgoingAction = containsAny(
            content,
            listOf("消费", "支出", "扣款", "支付", "转出", "快捷支付", "交易金额") +
                sanitizeKeywords(customKeywords)
        )
        return if (bankIdentity && outgoingAction) "bank_app" else null
    }

    private fun extractAmount(content: String): Double? {
        data class Candidate(val amount: Double, val score: Int, val position: Int)

        val candidates = mutableListOf<Candidate>()
        amountPatterns.forEach { pattern ->
            pattern.findAll(content).forEach { match ->
                val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
                val start = (match.range.first - 18).coerceAtLeast(0)
                val end = (match.range.last + 19).coerceAtMost(content.length)
                val context = content.substring(start, end)
                var score = 0
                if (containsAny(context, listOf("消费", "支出", "扣款", "支付", "付款", "交易金额", "转出"))) score += 4
                if (containsAny(context, listOf("余额", "可用额度", "可用余额", "账户余额"))) score -= 6
                candidates += Candidate(value, score, match.range.first)
            }
        }
        return candidates
            .filter { it.amount > 0.0 }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.position })
            .firstOrNull()
            ?.amount
    }

    private fun inferCategory(content: String): String? {
        return categoryRules.entries.firstOrNull { (_, keywords) ->
            keywords.any(content::contains)
        }?.key
    }

    private fun inferPaymentChannel(source: String, content: String): String {
        return when (source.lowercase(Locale.ROOT)) {
            "wechat" -> "微信支付"
            "alipay" -> "支付宝"
            "pinduoduo" -> "拼多多"
            "bank_sms", "bank_app" -> when {
                content.contains("财付通") -> "财付通"
                content.contains("支付宝") -> "支付宝"
                content.contains("微信支付") -> "微信支付"
                content.contains("云闪付") -> "云闪付"
                content.contains("京东支付") -> "京东支付"
                else -> "银行卡"
            }
            else -> sourceLabel(source)
        }
    }

    /**
     * 优先从单个通知字段提取交易对象，避免把拼接后的标题、金额和重复文案一起存入。
     * 无法可靠提取时返回 null，由界面明确显示“交易对象未提供”。
     */
    private fun extractCounterparty(data: PaymentNotificationContent, source: String): String? {
        val directPatterns = when (source.lowercase(Locale.ROOT)) {
            "wechat" -> listOf(
                Regex("""(?:付款给|支付给|转账给)\s*(.+)""")
            )
            "alipay" -> listOf(
                Regex("""(?:付款给|支付给|转账给)\s*(.+)"""),
                Regex("""(?:收款方|交易对象|商户(?:名称)?|商家)\s*[：:]\s*(.+)""")
            )
            "bank_sms", "bank_app" -> listOf(
                Regex("""(?:支出|消费)\s*[（(]([^()（）]+)[)）]"""),
                Regex("""(?:付款给|支付给|转账给|收款方|交易对象|商户(?:名称)?|商家)\s*[：:]\s*(.+)"""),
                Regex("""(?:在|于)\s*([^，,。；;]{2,60})\s*(?:消费|支付)""")
            )
            else -> listOf(
                Regex("""(?:付款给|支付给|转账给|收款方|交易对象|商户(?:名称)?|商家)\s*[：:]\s*(.+)"""),
                Regex("""(?:支出|消费)\s*[（(]([^()（）]+)[)）]"""),
                Regex("""(?:在|于)\s*([^，,。；;]{2,60})\s*(?:消费|支付)""")
            )
        }

        data.contentParts().forEach { part ->
            directPatterns.forEach { pattern ->
                val captured = pattern.find(part)?.groupValues?.getOrNull(1)
                sanitizeCounterparty(captured)?.let { return it }
            }
        }

        if (source == "pinduoduo") {
            return "拼多多平台商户"
        }
        return null
    }

    private fun sanitizeCounterparty(value: String?): String? {
        if (value.isNullOrBlank()) return null

        val cleaned = value
            .trim()
            .replace(Regex("""^(?:消费|支出|快捷支付)\s*"""), "")
            .replace(Regex("""^(?:财付通|支付宝|微信支付|云闪付|京东支付)\s*[-－—]\s*"""), "")
            .replace(Regex("""\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*元$"""), "")
            .replace(Regex("""[，,。；;]\s*(?:余额|可用余额|账户余额).*$"""), "")
            .trim(' ', '-', '－', '—', '：', ':')
            .take(200)

        val ignored = listOf("微信支付", "支付宝", "支付助手", "交易提醒", "订单通知", "动账通知")
        return cleaned.takeIf { it.length >= 2 && it !in ignored }
    }

    private fun sanitizeKeywords(keywords: List<String>): List<String> {
        return keywords.map(String::trim).filter { it.length >= 2 }
    }

    private fun containsAny(content: String, keywords: List<String>): Boolean {
        return keywords.any { it.isNotBlank() && content.contains(it, ignoreCase = true) }
    }

    private fun sourceLabel(source: String): String = when (source.lowercase(Locale.ROOT)) {
        "wechat" -> "微信支付"
        "alipay" -> "支付宝"
        "pinduoduo" -> "拼多多"
        "bank_sms" -> "银行卡短信"
        "bank_app" -> "银行应用"
        else -> "电子支付"
    }
}

data class PaymentNotificationContent(
    val packageName: String,
    val title: String = "",
    val titleBig: String = "",
    val text: String = "",
    val subText: String = "",
    val summaryText: String = "",
    val bigText: String = "",
    val infoText: String = "",
    val tickerText: String = "",
    val channelId: String = ""
) {
    fun contentParts(): List<String> {
        return listOf(bigText, text, tickerText, summaryText, infoText, titleBig, subText, title)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    fun allContent(): String {
        return contentParts().joinToString(" ")
    }
}

data class PaymentMatch(
    val amount: Double,
    val source: String,
    val categoryHint: String?,
    val description: String,
    val paymentChannel: String,
    val counterparty: String?
)
