package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine

/** 地址提取：从 OCR 行流中定位站点名/柜号/完整取件地址（自 CodeExtractor 拆出，R1）。
 *  品牌知识（【】品牌、餐饮关键词）来自 [BrandResolver]。 */
object AddressExtractor {

    internal enum class StationType { LOCKER, PICKUP_POINT, UNKNOWN }

    internal data class PickupLocation(
        val stationName: String,
        val stationType: StationType,
        val cabinetNumber: String?,
        val fullAddress: String
    )

    // 地址指示符（isAddressLike 核心判断）：合并反编译 App sources extractAddress 的 30+ 地标词表，
    // 覆盖 店/铺/站/点/园/苑/广场/中心/公寓/写字楼 等常见地址结尾，减少 S10 兜底漏抓真实地址。
    // 注意保留“元”仅在“单元”语境（见 isAddressLike 的 bareYuanOnly 处理）。
    private val ADDR_PIPE_FULL = Regex("[路街巷弄号栋幢单元柜室楼区县镇乡村庄店铺站点园苑院屋所广场中心商厦厦居宅房寓庭墅阁舍江河港湾门口岸桥山岭岗场]")
    private val ADDR_LANDMARK = Regex("(菜鸟|驿站|快递柜|丰巢|超市|诊所|对面|门口|小区|大厦|医院|银行|学校|商场|广场|中心|公寓|写字楼|工业园|科技园|物流园|产业园|代收点|便利店|商行|门面|花园|家园|宿舍|中学|孵化园)")
    private val ADDR_AFTER_TO = Regex("到(.+?)(领取|取件|门店|取运单尾号|取运单|取您|取你的|取貨|取货|取走|取你)")
    private val ADDR_LABEL = Regex("地址[:：]\\s*(.+)")
    private val ADDR_PLACED = Regex("(?:已放至|已暂存至|已放入|送达)\\s*([^，,。.\\n]{4,80})")
    private val CABINET_NUM = Regex("(\\d+)号柜")
    private val PAREN_ADDR = Regex("\\uFF08([^\\uFF09]*[路街段柜])\\uFF09")

    // 营销横幅/优惠标签词——出现这些词的片段不是店名/站名（如【新店福利】、满减、优惠券）
    private val PROMO_LABEL_WORDS = listOf("福利", "优惠", "满减", "红包", "立减", "折扣", "特惠", "会员")
    private val PING_NOISE_TRAIL = Regex("凭\\s*[A-Za-z0-9\\-]+\\s*$")

    private val STATION_TYPE_MAP = mapOf(
        "丰巢" to StationType.LOCKER, "欢猫智柜" to StationType.LOCKER,
        "快递柜" to StationType.LOCKER,
        "菜鸟驿站" to StationType.PICKUP_POINT, "妈妈驿站" to StationType.PICKUP_POINT,
        "兔喜" to StationType.PICKUP_POINT, "免喜" to StationType.PICKUP_POINT,
        "快递超市" to StationType.PICKUP_POINT, "韵达超市" to StationType.PICKUP_POINT,
        "代收点" to StationType.PICKUP_POINT
    )

    // ---------------------------------------------------------------
    // Address extraction (structured)
    // ---------------------------------------------------------------

    internal fun extractLocation(lines: List<OCREngine.TextLine>, allText: String): PickupLocation {
        var stationName = ""
        var fullAddress = ""
        var cabinet: String? = null
        var addrFrom = "none"

        // S-Coupon: 券码/到店券的门店识别（最高优先级）
        // 场景：外卖/到店券（德克士、蜜雪冰城等）截图，用户要的是“适用门店”（如 蜜雪冰城(老十字街店)），
        // 而非配送地址/周边地址。信号：待使用/用券/到店取/适用门店/立即用券/到店使用/券号 等券码上下文。
        val couponContext = listOf(
            "券号", "用券", "到店取", "到店使用", "待使用", "适用门店", "立即用券",
            "再次使用", "已使用", "兑换", "代金券", "优惠券", "满减券"
        )
        val isCouponContext = couponContext.any { allText.contains(it) }
        if (isCouponContext && fullAddress.isEmpty()) {
            // 优先扫描逐行，找“品牌名(店名)”格式（蜜雪冰城(老十字街店) / 德克士(郸械万果园店)）
            // 注意：括号字符类易触发 ICU 正则“incorrectly nested parentheses”，
            // 故不用单条大正则，改用简单匹配 + 字符串定位，稳妥且兼容。
            var couponAddr = ""
            var couponStation = ""
            for (line in lines) {
                val t = line.text.trim()
                // 用全角/半角开括号定位门店串：形如 品牌(店名) 或 品牌（店名）
                val openIdx = t.indexOfAny(charArrayOf('(', '（'))
                if (openIdx < 1) continue
                val afterOpen = t.substring(openIdx + 1)
                // 闭括号位置（全角/半角）
                val closeP = afterOpen.indexOf(')'); val closeF = afterOpen.indexOf('）')
                val closeIdx = when {
                    closeP >= 0 && closeF >= 0 -> minOf(closeP, closeF)
                    closeP >= 0 -> closeP
                    closeF >= 0 -> closeF
                    else -> -1
                }
                if (closeIdx <= 0) continue
                val brandPart = t.substring(0, openIdx).trim()
                val paren = afterOpen.substring(0, closeIdx).trim()
                // 品牌前导需为 2~10 个汉字；括号名需以“店”结尾且不含数字（排除快递员电话括号）
                if (!brandPart.matches(Regex("[\\u4e00-\\u9fff]{2,10}"))) continue
                if (!paren.endsWith("店") || paren.any { it.isDigit() }) continue
                // 完整门店串 = t 中从行首到闭括号的整段（基于原始行重建，避免 trimmed paren 导致丢字）
                val full = t.substring(0, openIdx) + t[openIdx] + afterOpen.substring(0, closeIdx + 1)
                // 行内门店信号：命中（已通过品牌前导校验的前提下）再认。
                // 注意：品牌前导校验已足够窄，此处要求额外的“到店/适用门店/营业中”这类券码信号，
                // 以提高精确度——避免把正文里任意 “X(某店)” 当门店（PRD：只认券码截图的门店）。
                val brandHits = BrandResolver.FOOD_BRAND_KEYWORDS.any { brandPart.contains(it, ignoreCase = true) }
                val storeSig =
                    t.contains("营业中") || t.contains("适用门店") || t.contains("到店") ||
                        t.contains("用券") || t.contains("门店") || brandHits
                if (!storeSig) continue
                couponAddr = full
                couponStation = full
                break
            }
            if (couponAddr.isNotEmpty()) {
                fullAddress = couponAddr.take(80)
                stationName = couponStation
                addrFrom = "SCoupon-store"
                // 若 isAddressLike 校验不通过（如门店串太短/不含地址特征），仍保留但降级以不改后续逻辑
            }
        }

        // S1: 【】 bracket brand for station name
        // 优先取含站点/快递关键词的括号；跳过快递员姓名+电话的括号（如【刘趁义:19037835253】）
        // 注意：不设“取第一个括号”的兜底——否则快递员括号会误当站名，留空交给后面的分支补全
        val bracketMatches = BrandResolver.BRACKET_BRAND.findAll(allText).map { it.groupValues[1].trim() }.toList()
        val goodBracket = bracketMatches.firstOrNull { content ->
            // 跳过包含手机号/运单号等数字的括号
            if (content.any { it.isDigit() }) return@firstOrNull false
            // 跳过优惠/福利/券类营销横幅（如【新店福利】是“新店优惠”标签，不是店名/站名）
            if (PROMO_LABEL_WORDS.any { content.contains(it) }) return@firstOrNull false
            STATION_TYPE_MAP.keys.any { content.contains(it) } ||
                ADDR_PIPE_FULL.containsMatchIn(content) ||
                ADDR_LANDMARK.containsMatchIn(content) ||
                listOf("店", "超市", "智柜", "生活", "代收", "驿站").any { content.contains(it) }
        }
        if (goodBracket != null) stationName = stripBrackets(goodBracket)

        // S0: 显式标签（取件地址/收货地址/代收点地址/取件点…）——最可靠的地址信号，最高优先级
        val explicit = listOf("取件点位置", "取件地址", "收货地址", "代收点地址", "取件点")
        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            for (p in explicit) {
                val i = line.text.indexOf(p)
                if (i < 0) continue
                var a = line.text.substring(i + p.length).trimStart(':', '：', ' ')
                for (sep in listOf('|', '｜')) {
                    val bar = a.indexOf(sep)
                    if (bar >= 0) { if (stationName.isEmpty()) stationName = extractStationName(a.substring(0, bar).trim()); a = a.substring(bar + 1).trim() }
                }
                a = cleanAddress(a)
                // 标签后若空/太短（值可能在下一行），向下拼 1~2 行续行
                if (fullAddress.isEmpty() && !isAddressLike(a) && lineIdx + 1 < lines.size) {
                    val cont = StringBuilder()
                    for (j in lineIdx + 1 until minOf(lineIdx + 3, lines.size)) {
                        val c = lines[j].text.trim()
                        if (c.isEmpty()) continue
                        if (c.first().isDigit() || c.startsWith("|")) break
                        cont.append(c)
                    }
                    val combined = cleanAddress(a + cont.toString())
                    if (isAddressLike(combined)) a = combined
                }
                // 折叠地址补全：S0-label 抓到的地址若只到省市区层级（缺路/街/号/店等街道/网点特征），
                // 折叠地址补全：S0-label 抓到的地址可能是被 UI 折叠的短串（如“…育新北展开”或“【xx店:..”），
                // 而同屏另有更具体的完整街道地址行（快递正文，如 育新路北段爱玛电动车旁边）。
                // 判断标准：存在比 a 更长、像地址、无折叠残留(未闭合括号/省略号/展开) 且含明确街道特征的行 → 就用它替换。
                val streetLike = listOf("路", "街", "巷", "弄", "道", "号店", "小区", "苑", "大厦", "超市", "驿站", "快柜", "智柜", "村", "庄")
                val adminLike = listOf("省", "市", "县", "区")
                // 折叠残留检测：a 以未闭合括号开头（如 【xx店，右括号被截断），或含 …/.. 省略号痕迹
                val uncleanA = a.startsWith("【") || a.startsWith("（") || a.startsWith("(") ||
                    a.contains("..") || a.contains("…")
                val better = lines
                    .map { it.text.trim() }
                    .filter {
                        it.length in 6..60 &&
                            it.length > a.length &&
                            streetLike.any { s -> it.contains(s) } &&
                            adminLike.none { ad -> it.contains(ad) } &&
                            listOf("电话", "..", "拨打", "联系", "展开", "【", "（", "(").none { it2 -> it.contains(it2) } &&
                            isAddressLike(it)
                    }
                    .maxByOrNull { it.length }
                // 条件：a 有折叠残留，或（a 是地址但缺明确街道特征时，且能找到更长完整行）→ 替换
                if (better != null && better != a &&
                    (uncleanA || streetLike.none { a.contains(it) })) {
                    a = better
                }
                if (fullAddress.isEmpty() && isAddressLike(a)) {
                    fullAddress = a.take(80)
                    addrFrom = "S0-label"
                    if (stationName.isEmpty()) stationName = extractStationName(a)
                }
            }
        }
        // S0b: 地址: 后跟收货地址（如 收货地址:河南省周口市郸城县育新北…）
        // 逐行匹配标签值，避免 ADDR_LABEL 在整屏 allText 上贪婪匹配到无关行尾噪声
        if (fullAddress.isEmpty()) {
            val labelLine = lines.firstOrNull { it.text.trim().contains(Regex("地址[:：]")) }
            if (labelLine != null) {
                val a0 = cleanAddress(ADDR_LABEL.find(labelLine.text)?.groupValues?.get(1).orEmpty())
                // ①同前缀更长地址行(完整地址与标签同屏出现时优先)
                var a = a0
                if (isAddressLike(a0) && a0.length >= 4) {
                    val p4 = a0.substring(0, 4)
                    val byPrefix = lines
                        .map { it.text.trim() }
                        .filter { it.length > a0.length && it.startsWith(p4) && isAddressLike(it) }
                        .maxByOrNull { it.length }
                    if (byPrefix != null) a = byPrefix
                }
                // ②标签值退化(短/OCR读重如 地址:育新路育新路育)时，取「标签行下方邻近」的干净完整地址，
                // 按 labelLine 的 y 定位同一通知卡片区域，避免错抓同屏其它驿站(不同通知)的地址。
                // 用彼此重复兜底：标签行下方的更长地址行优先于退化标签值。
                val labY = labelLine.boundingBox?.let { it.top.toFloat() } ?: 0f
                val nearbyBest = lines
                    .filter { tl ->
                        val y = tl.boundingBox?.let { it.top.toFloat() } ?: 0f
                        y > labY && y - labY < 300f && tl.text.trim().length > a0.length
                    }
                    .map { it.text.trim() }
                    .filter { it.length >= 4 && isAddressLike(it) }
                    .maxByOrNull { it.length }
                if (nearbyBest != null) a = nearbyBest
                if (isAddressLike(a)) {
                    fullAddress = a.take(80)
                    addrFrom = "S0b-addrLabel"
                    if (stationName.isEmpty()) stationName = extractStationName(a)
                }
            }
        }

        // S0c: 两列键值布局（5G消息卡片）——标签在左列，值在右列同一横带，地址续行在下方同列
        // 例：LINE[取件地址 y=942 x=107] + LINE[育新路北段店 y=942 x=380] + LINE[育新路…爱玛电动车 y=1032 x=380]
        if (fullAddress.isEmpty()) {
            val labelKw = listOf("取件地址", "取件点位置", "代收点地址", "取件点", "地址")
            for (labLine in lines) {
                val labBox = labLine.boundingBox ?: continue
                if (!labelKw.any { labLine.text.contains(it) }) continue
                // 找同一横带的右侧值行（y 接近 + 值在标签右边）
                val valueLine = lines.firstOrNull { v ->
                    val vb = v.boundingBox ?: return@firstOrNull false
                    vb !== labBox &&
                        kotlin.math.abs(vb.centerY() - labBox.centerY()) < 60 &&
                        vb.left > labBox.right
                } ?: continue
                // 拼接值行 + 下方同列（地址续行）
                val valueTxt = valueLine.text.trim()
                val valueBox = valueLine.boundingBox ?: continue
                val sb = StringBuilder()
                var curY = valueBox.bottom
                for (contLine in lines) {
                    val cb = contLine.boundingBox ?: continue
                    if (cb.centerY() > curY && cb.centerY() - curY < 120 &&
                        kotlin.math.abs(cb.left - valueBox.left) < 40) {
                        sb.append(contLine.text.trim())
                        curY = cb.bottom
                    }
                }
                val contTxt = sb.toString()
                // 若续行已包含取值行的核心地址（前4字），直接用更完整的续行，避免重复拼接
                // （例：取值行=育新路北段店，续行=育新路育新路育新路北段爱玛电动车旁边 → 只用续行）
                val core = if (valueTxt.length >= 4) valueTxt.substring(0, 4) else valueTxt
                val usesValue = valueTxt.length < 4 || !contTxt.contains(core)
                val a = cleanAddress(if (usesValue) (valueTxt + contTxt) else contTxt)
                if (fullAddress.isEmpty() && a.isNotEmpty() && isAddressLike(a)) {
                    fullAddress = a.take(80)
                    addrFrom = "S0c-column"
                    if (stationName.isEmpty()) stationName = extractStationName(a)
                }
            }
        }

        // S2: pipe-separated "shop | address"
        if (fullAddress.isEmpty()) {
            for (line in lines) {
                for (sep in listOf('|', '｜')) {
                    val bar = line.text.indexOf(sep)
                    if (bar < 0) continue
                    val left = line.text.substring(0, bar).trim()
                    val right = line.text.substring(bar + 1).trim()
                    if (stationName.isEmpty() && isAddressLike(left).not() && left.isNotBlank()) {
                        stationName = extractStationName(left)
                    }
                    if (fullAddress.isEmpty() && right.isNotBlank() && isAddressLike(right)) {
                        // 长地址可能被 OCR 拆到相邻多行：先向下拼 1~3 行，拼完仍像地址且非空则用拼接结果，否则退回单行
                        val parts = mutableListOf(right)
                        var cursorY = line.boundingBox?.bottom
                        for (j in lines.indexOf(line) + 1 until minOf(lines.indexOf(line) + 4, lines.size)) {
                            val n = lines[j]
                            val nBox = n.boundingBox
                            if (nBox != null && cursorY != null && nBox.top - cursorY > 600) break
                            val nt = n.text.trim()
                            if (nt.isEmpty()) continue
                            if (nt.length < 2 || nt.first().isDigit() || nt.startsWith("|") ||
                                listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知").any { nt.contains(it) }) break
                            parts.add(nt)
                            cursorY = nBox?.bottom ?: cursorY
                        }
                        val joinedAddr = parts.joinToString("")
                        val finalAddr = if (isAddressLike(joinedAddr) && joinedAddr.length > right.length) joinedAddr else right
                        fullAddress = stripBrackets(finalAddr).take(80); addrFrom = "S2-pipe"
                    }
                }
            }
        }

        // S5: "已放至/已暂存至" pattern
        if (fullAddress.isEmpty()) {
            ADDR_PLACED.find(allText)?.let { m ->
                val a = m.groupValues[1].trim()
                if (isAddressLike(a)) { fullAddress = stripBrackets(a).take(80); addrFrom = "S5-placed" }
            }
        }

        // S6: "到...取件/领取/门店/取运单" template (SMS/APP style)
        // Scope to lines containing 凭/取件 to avoid greedy match across unrelated 到 in joined text.
        // 合并自 S6a（单行）+ S6b（跨行）：先试单行，单行失败再向前拼 1~3 行，触发词判断与后处理复用同一套。
        if (fullAddress.isEmpty()) {
            for (i in lines.indices) {
                val line = lines[i]
                val hasTrigger = line.text.contains("凭") || line.text.contains("取件") || line.text.contains("取运单") ||
                    line.text.contains("取您") || line.text.contains("取你的") || line.text.contains("取走")
                if (!hasTrigger) continue

                // S6a: 单行内匹配
                ADDR_AFTER_TO.find(line.text)?.let { m6 ->
                    val a = m6.groupValues[1].trim()
                    val clean = a.replace(PING_NOISE_TRAIL, "").trim()
                    if (isAddressLike(clean)) {
                        fullAddress = stripBrackets(clean).take(80)
                        addrFrom = "S6a"
                        if (stationName.isEmpty()) stationName = extractStationName(clean)
                        return@let
                    }
                }
                if (fullAddress.isNotEmpty()) break

                // S6b: 跨行匹配——OCR 常把「到<地址>」和「取运单…」拆成多个 TextLine，
                // 拼接前 1~3 行（结束词「取您/取件」可能在 3 行外）
                for (span in 1..3) {
                    if (i - span < 0) break
                    val start = i - span
                    val combined = (start until i).joinToString(" ") { lines[it].text } + " " + line.text
                    ADDR_AFTER_TO.find(combined)?.let { m6 ->
                        val a = m6.groupValues[1].trim()
                        val clean = a.replace(PING_NOISE_TRAIL, "").trim()
                        if (isAddressLike(clean)) {
                            fullAddress = stripBrackets(clean).take(80)
                            addrFrom = "S6b"
                            if (stationName.isEmpty()) stationName = extractStationName(clean)
                            return@let
                        }
                    }
                }
                if (fullAddress.isNotEmpty()) break
            }
        }

        // S7: cabinet number + address from "号柜" line
        for (idx in lines.indices) {
            val line = lines[idx]
            if (!line.text.contains("号柜")) continue
            CABINET_NUM.find(line.text)?.let { cabinet = it.groupValues[1] }
            var s7addr = stripBrackets(line.text.trim())
            // 若本行不够像地址（柜号行常只有「2号柜」），向上拼 1~2 行的地址前缀
            if (!isAddressLike(s7addr) && idx > 0) {
                val up = StringBuilder()
                for (j in (idx - 2).coerceAtLeast(0) until idx) {
                    val u = lines[j].text.trim()
                    if (u.isEmpty()) continue
                    up.append(u)
                }
                val cand = (up.toString() + s7addr)
                if (isAddressLike(cand)) s7addr = cand.take(80)
            }
            if (fullAddress.isEmpty() && isAddressLike(s7addr))
                { fullAddress = stripBrackets(s7addr).take(80); addrFrom = "S7-cabinet" }
        }

        // S8: nearby lines after prefix keywords
        val prefixes = explicit + listOf("代收点", "地址", "号柜")
        for (i in lines.indices) {
            if (!prefixes.any { lines[i].text.contains(it) }) continue
            if (stationName.isEmpty()) stationName = extractStationName(lines[i].text)
            for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                if (fullAddress.isNotEmpty()) break
                val n = lines[j].text.trim()
                if (!isAddressLike(n)) continue
                // 命中后向下拼 1~2 行地址续行（跳过单字/数字/噪声行）
                var s8addr = stripBrackets(n).take(80)
                if (s8addr.length < 80) {
                    val contParts = mutableListOf(s8addr)
                    for (k in j + 1 until minOf(j + 3, lines.size)) {
                        val c = lines[k].text.trim()
                        if (c.isEmpty()) break
                        if (c.length < 2 || c.first().isDigit() || c.startsWith("|") ||
                            listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知").any { c.contains(it) }) break
                        contParts.add(c)
                    }
                    val joined = contParts.joinToString("")
                    if (isAddressLike(joined)) s8addr = joined.take(80)
                }
                fullAddress = s8addr; addrFrom = "S8-nearby"; break
            }
        }

        // S9: parenthesized address
        if (fullAddress.isEmpty()) {
            for (line in lines) {
                val m = PAREN_ADDR.find(line.text)
                if (m != null && isAddressLike(m.groupValues[1])) {
                    fullAddress = stripBrackets(m.groupValues[1]).take(80); addrFrom = "S9-paren"; break
                }
            }
        }

        // S10: fallback - any line with road/street/cabinet indicators
        if (fullAddress.isEmpty()) {
            for (idx in lines.indices) {
                val line = lines[idx]
                if (stationName.isEmpty()) stationName = extractStationName(line.text)
                if (!line.text.contains(ADDR_PIPE_FULL) || !isAddressLike(line.text.trim())) continue
                // 向下续行拼接：OCR 常把地址拆成相邻多行（如 申通快/谦/申通快递）
                var addrBase = stripBrackets(line.text.trim())
                if (addrBase.length < 80) {
                    var cursorY = line.boundingBox?.bottom
                    val parts = mutableListOf(addrBase)
                    for (j in idx + 1 until minOf(idx + 4, lines.size)) {
                        val n = lines[j]
                        val nBox = n.boundingBox
                        // 纵 gap 过大（>600px）说明不是同一地址块，停止
                        if (nBox != null && cursorY != null && nBox.top - cursorY > 600) break
                        val nt = n.text.trim()
                        if (nt.isEmpty()) continue
                        // 过滤单字错字（OCR 拆出的笔画字，如 谦）与非地址噪声行
                        if (nt.length < 2) continue
                        if (nt.first().isDigit() || nt.startsWith("|") || nt.startsWith("●") ||
                            listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知").any { nt.contains(it) }) break
                        parts.add(nt)
                        cursorY = nBox?.bottom ?: cursorY
                    }
                    val joined = parts.joinToString("")
                    if (isAddressLike(joined)) { addrBase = joined.take(80) } else { addrBase = parts.first() }
                }
                fullAddress = addrBase; addrFrom = "S10-fallback"; break
            }
        }

        // 后处理：快递柜/智能柜识别——在取件地址后追加柜名+柜号，并修正站名
        val lockerName = STATION_TYPE_MAP.entries
            .filter { it.value == StationType.LOCKER }
            .map { it.key }
            .sortedByDescending { it.length }
            .firstOrNull { allText.contains(it) }
        if (lockerName != null) {
            // 劣质站名替换：动作前缀（已放入/待取件…）或“含数字的商品/规格名”（如 4.5英寸昧碟）都不是站名 → 换成柜名
            val stationLooksBad = listOf("已放入", "已放至", "已暂存", "待取件", "待取", "已派送").any { stationName.startsWith(it) } ||
                (stationName.any { it.isDigit() } &&
                    STATION_TYPE_MAP.keys.none { stationName.contains(it) } &&
                    ADDR_PIPE_FULL.containsMatchIn(stationName).not())
            if (stationName.isNotEmpty() && stationName != lockerName && stationLooksBad) {
                stationName = lockerName
            }
            if (fullAddress.isNotEmpty() && !fullAddress.contains(lockerName)) {
                fullAddress = (fullAddress + lockerName).take(80)
            }
            // 追加柜号（如 2号柜）：地址若没有“X号柜”则补上
            if (cabinet != null && cabinet!!.isNotEmpty() &&
                !Regex("\\d+号柜").containsMatchIn(fullAddress)) {
                fullAddress = (fullAddress + cabinet + "号柜").take(80)
            }
        }

        // 折叠 OCR 重复的路名/站名（对 S2/S5/S10 等未走 cleanAddress 的路径也生效）
        fullAddress = dedupeRepeated(fullAddress)

        // Determine station type
        val stype = classifyStation(stationName, fullAddress, allText)

        // If station name still empty, try to extract from full address or all text
        if (stationName.isEmpty()) {
            stationName = extractStationName(fullAddress)
        }
        if (stationName.isEmpty()) {
            stationName = extractStationName(allText)
        }

        android.util.Log.d("CodeExtrDiag",
            "ADDR=full=[$fullAddress] from=[$addrFrom] station=[$stationName] cabinet=[$cabinet] type=[$stype] allText=" + allText)

        return PickupLocation(
            stationName = stationName.ifEmpty { "未知站点" },
            stationType = stype,
            cabinetNumber = cabinet,
            fullAddress = fullAddress
        )
    }

    /**
     * 按码定位提取专属地址（多驿站通知中心场景）。
     * 在「码所在行附近的通知卡片窗口」内找该码的取件地址，而不是全屏抓一个地址。
     * 码行 ±3 行 且 y 距离 ≤ 400px 视为同一通知卡片。
     */
    fun extractAddressForCode(lines: List<OCREngine.TextLine>, code: String): String {
        if (lines.isEmpty()) return ""
        val codeIdx = lines.indexOfFirst { it.text.contains(code) }
        if (codeIdx < 0) return ""
        val codeBoxTop = lines[codeIdx].boundingBox?.let { it.top.toFloat() }

        fun inWindow(otherIdx: Int): Boolean {
            if (otherIdx == codeIdx) return false
            if (kotlin.math.abs(otherIdx - codeIdx) > 3) return false
            val b = lines[otherIdx].boundingBox ?: return true
            val cIdxBox = lines[codeIdx].boundingBox
            if (cIdxBox != null && codeBoxTop != null) {
                return kotlin.math.abs(b.top.toFloat() - codeBoxTop) <= 400f
            }
            return true
        }

        // 窗口内的行（按 y 排序，从码行下方优先——地址/取件说明通常在码下方）
        val windowLines = lines
            .filterIndexed { i, _ -> inWindow(i) }
            .sortedBy { it.boundingBox?.top ?: 0 }
        if (windowLines.isEmpty()) return ""
        val windowText = windowLines.joinToString(" ") { it.text }

        // 优先级 1：S6 「到…取件/取用」句式（通知体最常见的地址锚点）
        // 地址可能跨行（LINE8“…到育新路与季庄街…社区卫生” + LINE9“所对面2号柜H36…取您的快递”）
        // 仅在本码 ±3 行的窗口内找；含「到」即尝试（同码头尾地址常在码行，无需同行的取件词）
        val lo = (codeIdx - 3).coerceAtLeast(0)
        val hi = (codeIdx + 3).coerceAtMost(lines.lastIndex)
        for (i in lo..hi) {
            val t = lines[i].text
            if (!t.contains("到")) continue
            // 单行先试（排除"到达/已到达"动词：捕获以"达"开头说明是"到达xx"误抽，非地址介词"到"）
            ADDR_AFTER_TO.find(t)?.let { m6 ->
                val clean0 = m6.groupValues[1].trim().replace(PING_NOISE_TRAIL, "").trim()
                if (!clean0.startsWith("达") && isAddressLike(clean0)) return stripBrackets(clean0).take(80)
            }
            // 跨行向下拼接 1~3 行
            for (span in 1..3) {
                if (i + span >= lines.size) break
                val combined = (i..i + span).joinToString("") { lines[it].text }
                ADDR_AFTER_TO.find(combined)?.let { m6b ->
                    val clean0 = m6b.groupValues[1].trim().replace(PING_NOISE_TRAIL, "").trim()
                    if (!clean0.startsWith("达") && isAddressLike(clean0)) return stripBrackets(clean0).take(80)
                }
            }
        }

        // 优先级 2：地址: 标签
        // 优先级 2：地址: 标签（含退化标签补全——如 地址:育新路育新路育 时取下方干净地址行）
        val labLine = windowLines.firstOrNull { it.text.contains(Regex("地址[:：]")) }
        if (labLine != null) {
            val a0 = cleanAddress(ADDR_LABEL.find(labLine.text)?.groupValues?.get(1).orEmpty())
            if (isAddressLike(a0) && a0.length >= 4) {
                // 前缀命中同行更完整行 或 同前缀更长行
                val p4 = a0.substring(0, 4)
                val byPrefix = windowLines.map { it.text.trim() }
                    .filter { it.length > a0.length && it.startsWith(p4) && isAddressLike(it) }
                    .maxByOrNull { it.length }
                if (byPrefix != null) return byPrefix.take(80)
            }
            // 标签值退化/短时：取标签行下方邻近的干净完整地址（同一通知卡片区域）
            val labY = labLine.boundingBox?.let { it.top.toFloat() } ?: 0f
            val nearby = windowLines
                .filter { tl ->
                    val y = tl.boundingBox?.let { it.top.toFloat() } ?: 0f
                    y > labY && y - labY < 300f && tl.text.trim().length > a0.length
                }
                .map { it.text.trim() }
                .filter { it.length >= 4 && isAddressLike(it) }
                .maxByOrNull { it.length }
            if (nearby != null) return nearby.take(80)
            if (isAddressLike(a0)) return a0.take(80)
        }

        // 优先级 3：窗口内最长的像地址行
        val best = windowLines
            .map { it.text.trim() }
            .filter { isAddressLike(it) }
            .maxByOrNull { it.length }
        return best?.take(80) ?: ""
    }

    /** Backward-compatible: return address string from structured location. */
    fun extractAddress(lines: List<OCREngine.TextLine>, allText: String): String {
        return extractLocation(lines, allText).fullAddress
    }

    /** 增强版：context 非空时优先匹配用户常用站点（借鉴反编译 App setCommonStations）。 */
    fun extractAddress(lines: List<OCREngine.TextLine>, allText: String, context: android.content.Context?): String {
        if (context == null) return extractAddress(lines, allText)
        val commonStations = com.pickupcode.app.learner.CommonStationStore.getCommonStations(context)
        if (commonStations.isEmpty()) return extractAddress(lines, allText)
        // S1b: 常用站点优先匹配——命中用户常去的驿站/快递柜/取件点，直接作为最可靠地址信号
        for (line in lines) {
            val t = line.text.trim()
            val hit = commonStations.firstOrNull { t.contains(it.name, ignoreCase = true) }
            if (hit != null) return t.take(80)
        }
        return extractAddress(lines, allText)
    }

    /**
     * 独立柜号提取（借鉴反编译 App extractCabinetInfo）：从取件文本里抓柜号/格口，
     * 如 2号柜、5号副柜、云柜12号、12号格口、A区3号柜。返回规范化串（含“柜/格口”后缀），
     * 无则空串。供入库时作为独立 cabinetNumber 字段保存（区别于拼进地址尾部）。
     */
    fun extractCabinetNumber(lines: List<OCREngine.TextLine>, allText: String): String {
        val texts = lines.map { it.text }.filter { it.isNotBlank() }
        // 优先整行完整柜号：X号[副/主]柜 / 云柜X号 / X号格口
        for (t in texts) {
            val m = Regex("(\\d+号(?:副|主)?柜|云柜\\d+号\\d+号格口|\\d+号格口|\\d+号丰巢柜|\\d+号[\\u4e00-\\u9fa5]{0,4}柜)")
                .find(t) ?: continue
            val v = m.value
            if (v.length <= 12) return v
        }
        // 兜底：纯 X号柜
        val plain = CABINET_NUM.find(allText)
        return if (plain != null && plain.groupValues[1].length <= 6) plain.groupValues[1] + "号柜" else ""
    }

    // ---------------------------------------------------------------
    // Station helpers
    // ---------------------------------------------------------------

    private fun extractStationName(text: String): String {
        // Try 【】 first (skip promo/voucher labels like 【新店福利】)
        BrandResolver.BRACKET_BRAND.findAll(text).forEach { m ->
            val c = m.groupValues[1].trim()
            if (c.any { it.isDigit() }) return@forEach
            if (PROMO_LABEL_WORDS.any { c.contains(it) }) return@forEach
            return stripBrackets(c)
        }
        // Try known station keywords
        for (kw in STATION_TYPE_MAP.keys) {
            if (text.contains(kw)) {
                // Extract the full station name: text before and including the keyword
                val idx = text.indexOf(kw)
                val start = (0 until idx).lastOrNull { text[it] in "，,。.；;、|｜ " }?.plus(1) ?: 0
                val end = (idx + kw.length until text.length)
                    .firstOrNull { text[it] in "，,。.；;、|｜ " } ?: text.length
                val name = text.substring(start, end).trim()
                if (name.length in 2..16) return name
            }
        }
        // 放宽：形如 X超市 / X便利店 / X商行 的店名（如 鮮佰汇超市）也当站点/收货点
        Regex("(?<![元券])[\\u4e00-\\u9fffA-Za-z0-9]{1,8}?(超市|便利店|商行)").find(text)?.let { m ->
            val name = m.groupValues[0].trim()
            if (name.length in 2..12 && PROMO_LABEL_WORDS.none { name.contains(it) }) return name
        }
        return ""
    }

    private fun classifyStation(stationName: String, address: String, allText: String): StationType {
        val combined = "$stationName $address $allText"
        for ((kw, type) in STATION_TYPE_MAP) {
            if (combined.contains(kw)) return type
        }
        return StationType.UNKNOWN
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /** 去掉取件地址开头/结尾的【】（）包围（OCR 常把站名/地址包在全角括号里）。 */
    private fun stripBrackets(s: String): String {
        var r = s.trim()
        while (r.isNotEmpty()) {
            val c0 = r.first(); val c9 = r.last()
            if ((c0 == '【' && c9 == '】') || (c0 == '(' && c9 == ')') || (c0 == '（' && c9 == '）')) {
                r = r.substring(1, r.length - 1).trim()
            } else break
        }
        // 处理不闭合的左括号（OCR/UI 折叠截断导致如 “【育新路北段店” 无右括号）：剥掉孤立左括号
        if (r.startsWith("【") && !r.contains("】")) r = r.removePrefix("【").trim()
        if (r.startsWith("（") && !r.contains("）")) r = r.removePrefix("（").trim()
        if (r.startsWith("(") && !r.contains(")")) r = r.removePrefix("(").trim()
        return r
    }

    /** 地址尾部 UI 噪音关键词，出现时截断（展开/复制/拨打电话 等按钮文案）。 */
    private val ADDR_TRAIL_NOISE = listOf(
        "展开", "收起", "复制", "订阅提醒", "拨打电话", "拨打", "查看物流", "确认收货", "物流电话", "联系驿站", "联系快递员",
        "分享", "号码保护", "虚拟号码", "已通过虚拟号码发货", "待取件", "物流服务", "物流信息"
    )

    /** 清理标签式地址：剥括号 + 在标点/噪音词处截断，取干净的地址前缀。 */
    private fun cleanAddress(s: String): String {
        var r = stripBrackets(s.trim())
        for (sep in ":：，,。.。;；…") {
            val idx = r.indexOf(sep)
            if (idx >= 0) r = r.substring(0, idx)
        }
        for (noise in ADDR_TRAIL_NOISE) {
            val idx = r.indexOf(noise)
            if (idx >= 0) r = r.substring(0, idx)
        }
        r = dedupeRepeated(stripBrackets(r.trim()))
        return r.trim()
    }

    /** 折叠连续 3 次以上重复的相邻片段（OCR 常把路名/站名读重，如 育新路育新路育新路→育新路）。
     *  只折叠 3+ 次重复，保留合法的双字重复（如站名里正常的两个相同字）。 */
    private fun dedupeRepeated(s: String): String {
        var r = s
        if (r.isEmpty()) return r
        for (len in 4 downTo 2) {
            val re = Regex("(.{$len})\\1{2,}")
            var prev = ""
            while (prev != r) {
                prev = r
                val m = re.find(r) ?: break
                // 用第一个匹配的重复单元长度做逐步折叠（处理同一串内多种重复）
                val unit = m.groupValues[1]
                r = r.replace(Regex(Regex.escape(unit) + "{3,}"), unit)
            }
        }
        return r
    }

    private fun isAddressLike(s: String): Boolean {
        val t = stripBrackets(s)
        if (t.length !in 4..80 || t.none { it in '\u4e00'..'\u9fff' }) return false
        // Must contain address indicators (road/street/building/cabinet etc)
        // 「元」会与货币/金额冲突（如 累计省4元>、¥9.9元、实付¥8.90）——只有 单元/几单元 里的 元 才算地址指示符
        val pipeChars = ADDR_PIPE_FULL.findAll(t).map { it.value }.toList()
        val hasStreet = pipeChars.isNotEmpty() || ADDR_LANDMARK.containsMatchIn(t)
        val bareYuanOnly = pipeChars.isNotEmpty() && pipeChars.all { it == "元" } && !t.contains("单元")
        if (!hasStreet || bareYuanOnly) return false
        // Exclude non-address strings that happen to contain a "号" indicator (e.g. 运单尾号)
        if (listOf("取运单", "运单尾号", "运单", "包裹", "删除").any { t.contains(it) }) return false
        // Exclude pickup-code prefix noise (e.g. OUCR 把「取件码」拆成 件码 紧跟码值，如 件码067865到…)
        if (listOf("件码", "取件码", "取货码", "提取码", "取餐码", "取单码").any { t.contains(it) }) return false
        // Exclude 运单号/单号 标签（如 OCR 误写的 快谨单号）——不是取件地址
        if (t.endsWith("单号") || listOf("运单号", "订单号", "快运单号", "快递单号").any { t.contains(it) }) return false
        // Exclude 订单/交易/UI 界面标签（如 OCR 把「订单详情」读成 订单详惰、交易快照、券号/券码等）——不是取件地址
        if (listOf("订单", "交易", "快照", "详惰", "详情页", "商品", "规格", "小计", "合计", "数量", "券码", "券号").any { t.contains(it) }) return false
        // OCR 把「详情/快照」等标签的字读错（详惰/快照）概率高，真实地址几乎不会以「详/惰」作实义词——单独拦以开头为详的标签串
        if (t.startsWith("详惰") || t.startsWith("订单")) return false
        // Exclude 隐私号/虚拟号/联系电话 等通知文案（带 **** 脱敏的手机信息），不是取件地址
        if (listOf("号码保护", "虚拟号码", "联系电话", "手机号", "客服电话", "已通过虚拟号码发货").any { t.contains(it) }) return false
        if (t.contains("****")) return false
        return listOf("展开", "复制", "拨打", "导航", "订阅", "延长收货", "查看物流", "确认收货").none { t.contains(it) }
    }
}
