package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine

/** 品牌解析：从 OCR 行/运单号推断取件来源品牌（餐饮店名/快递公司）。
 *  自 CodeExtractor 拆出（R1），供识别主流程与快递100验证器等复用。 */
object BrandResolver {

    // 运单号字母前缀匹配（社区规则，见 orderNumPrefixToBrand）
    private val REG_JP = Regex("^JP\\d{10,15}$")
    private val REG_JT = Regex("^JT\\d{10,15}$")
    private val REG_JD = Regex("^(JD|JDL)\\d{10,15}$")
    private val REG_JD_VA = Regex("^[VA]\\d{10,15}$")
    private val REG_SF = Regex("^SF\\d{10,15}$")
    private val REG_YT = Regex("^YT\\d{10,15}$")
    private val REG_YTO = Regex("^YTO\\d{8,14}$")
    private val REG_YD = Regex("^YD\\d{10,15}$")
    private val REG_YUNDA = Regex("^YUNDA\\d{8,14}$")
    private val REG_ZTO = Regex("^ZTO\\d{8,14}$")
    private val REG_ST = Regex("^ST\\d{10,15}$")
    private val REG_STO = Regex("^STO\\d{8,14}$")
    private val REG_EMS = Regex("^E[A-Z0-9]\\d{9}CN$")
    private val REG_POSTAL = Regex("^RA\\d{9,13}CN$")
    private val REG_DPK = Regex("^DPK\\d{10,15}$")
    private val REG_DPL = Regex("^DPL\\d{10,15}$")

    /** 【品牌】括号解析（CodeExtractor 与 AddressExtractor 共用）。 */
    internal val BRACKET_BRAND = Regex("【([^】]+)】")

    internal val FOOD_BRAND_KEYWORDS = listOf(
        // 咖啡
        "瑞幸", "luckin", "星巴克", "starbucks", "库迪", "cotti", "manner", "seesaw", "挪瓦咖啡", "nowwa", "幸运咖",
        // 快餐/西式
        "麦当劳", "mcdonald", "肯德基", "kfc", "德克士", "dicos", "汉堡王", "burger king", "华莱士", "塔斯汀", "必胜客", "pizza", "达美乐", "domino", "萨莉亚", "赛百味", "subway",
        // 茶饮/新式茶
        "喜茶", "heytea", "奈雪", "奈雪的茶", "蜜雪冰城", "霸王茶姬", "茶百道", "一点点", "coco", "书亦烧仙草", "书亦", "古茗", "茶颜悦色", "沪上阿姨", "甜啦啦", "益禾堂", "林里", "linlee", "茉莉奶白", "乐乐茶", "贡茶", "tims", "tims天好咖啡",
        // 中式快餐/粉面
        "老乡鸡", "真功夫", "沙县小吃", "兰州拉面", "兰州牛肉面", "杨国福", "张亮麻辣烫", "麻辣烫", "吉野家", "味千拉面", "和府捞面", "老娘舅", "大米先生", "乡村基",
        // 烘焙/甜品/小吃
        "鲍师傅", "好利来", "味多美", "巴黎贝甜", "面包新语",
        // 火锅/正餐/其他连锁
        "海底捞", "呷哺呷哺", "西贝", "西贝莜面村", "外婆家", "绿茶餐厅", "探鱼", "半天妖", "太二",
        // 卤味/鸡排等小吃连锁
        "正新鸡排", "正新", "绝味", "绝味鸭脖", "煌上煌", "紫燕百味鸡", "周黑鸭"
    )
    private val COURIER_BRANDS = listOf(
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔",
        "邮政快递", "邮政", "菜鸟", "丰巢", "妈妈驿站", "兔喜",
        "免喜", "韵达超市", "欢猫智柜"
    )

    // M11: 品牌+后缀正则一次性预编译（品牌固定，避免热循环里每个品牌每次调用都重新编译 Regex）
    private val FOOD_SUFFIXES = listOf("取餐", "外卖", "咖啡", "茶饮", "奶茶", "饮品", "点单", "鲜果", "门店")
    private val COURIER_SUFFIXES = listOf("快递", "速递", "物流", "速运", "超市", "驿站", "智能柜")
    private val FOOD_BRAND_SUFFIX_REGEX: List<Pair<String, Regex>> =
        FOOD_BRAND_KEYWORDS.map { it to Regex(Regex.escape(it) + "(?:" + FOOD_SUFFIXES.joinToString("|") { Regex.escape(it) } + ")", RegexOption.IGNORE_CASE) }
    private val COURIER_BRAND_SUFFIX_REGEX: List<Pair<String, Regex>> =
        COURIER_BRANDS.map { it to Regex(Regex.escape(it) + "(?:" + COURIER_SUFFIXES.joinToString("|") { Regex.escape(it) } + ")") }

    // Order/tracking number patterns (used for brand positioning)
    // 允许尾缀 CN：RA/EMS 单号（如 RA123456789CN、EA123456789CN）此前因尾缀 CN 无法命中 \b 而被漏抓
    private val COURIER_ORDER_NUM = Regex("""\b(?:[A-Z]{2,3}\d{8,14}(?:CN)?|RA\d{9,13}CN|\d{13,15}|\d{2,4}-\d{3,5}-\d{4,6})\b""")

    internal fun sourceFromLine(line: OCREngine.TextLine, hint: String, allLines: List<OCREngine.TextLine>, allText: String): String {
        // Strategy (ordered by reliability):
        // 1. Brand near order/tracking number (courier name usually before order number)
        // 2. Bracket brand validated against known brands
        // 3. Brand+suffix pattern (X快递, X速递, X物流...)
        // 4. Brand in ±3 lines from the code
        // 5. Fallback

        val codeLineIdx = allLines.indexOfFirst { it === line }

        // --- S0: Bracket brand on/nearest the code's own line (before global fallback) ---
        // Handles multi-notification shares where each 【品牌】 precedes its own code on the same block.
        val bracketOnLine: MutableList<String> = mutableListOf()
        if (codeLineIdx >= 0) {
            for (offset in sequenceOf(0, -1, 1, -2, 2)) {
                val t = allLines.getOrNull(codeLineIdx + offset)?.text ?: continue
                BRACKET_BRAND.findAll(t).forEach { bb -> bracketOnLine.add(bb.groupValues[1]) }
            }
        }
        for (content in bracketOnLine) {
            FOOD_BRAND_KEYWORDS.firstOrNull { content.contains(it, ignoreCase = true) }?.let { return it }
            COURIER_BRANDS.firstOrNull { content.contains(it) }?.let { return it }
        }

        // --- S1: Extract brand via order/tracking number positioning ---
        // Courier name typically appears before the tracking number.
        // Find order numbers, then grab the nearest brand before them.
        if (hint in listOf("parcel", "取件码", "取货码")) {
            extractBrandViaOrderNum(allText, allLines)?.let { return it }
        }

        // --- S2: Bracket brand validation ---
        BRACKET_BRAND.find(allText)?.let { m ->
            val content = m.groupValues[1].trim()
            FOOD_BRAND_KEYWORDS.firstOrNull { content.contains(it, ignoreCase = true) }
                ?.let { return it }
            COURIER_BRANDS.firstOrNull { content.contains(it) }
                ?.let { return it }
        }

        // --- S3: Brand+suffix at line level ---
        val brands = if (hint in listOf("food", "取餐码", "取餐号")) FOOD_BRAND_KEYWORDS else COURIER_BRANDS
        val ignoreCase = hint in listOf("food", "取餐码", "取餐号")
        val brandSuffixRegex = if (hint in listOf("food", "取餐码", "取餐号")) FOOD_BRAND_SUFFIX_REGEX else COURIER_BRAND_SUFFIX_REGEX

        fun brandWithSuffix(text: String): String? {
            // M11: 用预编译正则，避免每次调用逐品牌重新编译
            for ((brand, re) in brandSuffixRegex) {
                if (re.containsMatchIn(text)) return brand
            }
            return null
        }

        // S3a: Current line
        brandWithSuffix(line.text)?.let { return it }

        // S3b: Nearby lines (±3)
        if (codeLineIdx >= 0) {
            for (offset in sequenceOf(-1, 1, -2, 2, -3, 3)) {
                allLines.getOrNull(codeLineIdx + offset)?.let { neighbor ->
                    brandWithSuffix(neighbor.text)?.let { return it }
                }
            }
        }

        // --- S4: Global brand+suffix fallback ---
        brandWithSuffix(allText)?.let { return it }

        // --- S5: Proximity-based brand mention (any occurrence near code) ---
        if (codeLineIdx >= 0) {
            val contextLines = allLines.slice(maxOf(0, codeLineIdx - 3)..minOf(allLines.lastIndex, codeLineIdx + 3))
            val contextText = contextLines.joinToString(" ") { it.text }
            for (brand in brands.sortedByDescending { it.length }) {
                if (contextText.contains(brand, ignoreCase))
                    return brand
            }
        }

        return if (hint in listOf("food", "取餐码", "取餐号")) "餐饮" else "快递"
    }

    /** Extract courier brand by looking at text before order/tracking numbers,
     *  with order-number prefix as a stronger signal than OCR text matching. */
    private fun extractBrandViaOrderNum(allText: String, allLines: List<OCREngine.TextLine>): String? {
        val orderMatch = COURIER_ORDER_NUM.find(allText) ?: return null
        val orderNum = orderMatch.value
        val orderStart = orderMatch.range.first
        val textBefore = allText.substring(0, orderStart)

        // S1a: Order number prefix → brand mapping (most reliable, machine-generated)
        val prefixBrand = orderNumPrefixToBrand(orderNum)

        // S1b: Find the last brand mention before the order number in OCR text
        var textBrand: String? = null
        var bestPos = -1
        for (brand in COURIER_BRANDS.sortedByDescending { it.length }) {
            val idx = textBefore.lastIndexOf(brand)
            if (idx > bestPos) {
                bestPos = idx
                textBrand = brand
            }
        }

        // Prefix wins over text when they conflict (prefix is machine-generated, more reliable)
        return prefixBrand ?: textBrand
    }

    /** Map order/tracking number prefix to courier brand.
     *  参考开源社区（GitHub shallowlong/courier-rules, zhili007/kuaidi-tracker）。
     *  只对"字母前缀保留字"做可靠识别（字母段几乎无重叠、零误判）；
     *  纯数字段各家重叠严重（顺丰/中通/申通/韵达/EMS 都订阅了部分 12/13 位段），
     *  不再派到此处，交由 extractBrandViaOrderNum 的 textBrand 依据 OCR 文本中的品牌名判定。 */
    private fun orderNumPrefixToBrand(num: String): String? {
        val u = num.trim().uppercase()
        return when {
            REG_JP.matches(u) || REG_JT.matches(u) -> "极兔"
            REG_JD.matches(u) || REG_JD_VA.matches(u) -> "京东物流"
            REG_SF.matches(u) -> "顺丰"
            REG_YT.matches(u) || REG_YTO.matches(u) -> "圆通"
            REG_YD.matches(u) || REG_YUNDA.matches(u) -> "韵达"
            REG_ZTO.matches(u) -> "中通"
            REG_ST.matches(u) || REG_STO.matches(u) -> "申通"
            REG_EMS.matches(u) -> "EMS"
            REG_POSTAL.matches(u) -> "邮政"
            REG_DPK.matches(u) || REG_DPL.matches(u) -> "德邦"
            else -> null
        }
    }

    /** 公开：根据运单号前缀识别快递品牌（中文名）。供验证器等模块复用，避免规则重复。 */
    fun guessOrderBrand(num: String): String? = orderNumPrefixToBrand(num)

    /**
     * 公开：从 OCR 全文提取第一个运单/快递单号。
     * 供快递100验证器等模块反向查询取件码/地址使用。
     */
    fun findOrderNumber(allText: String): String? =
        COURIER_ORDER_NUM.find(allText)?.value
}
