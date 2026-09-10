# 识别语料回归（corpus）

把真实截图/短信的 OCR 行固化成**语料**，量化码识别的 precision / recall 与地址命中率。
每次改动正则、评分或地址管线，先看语料指标，再提交。

## 为什么需要

识别层是"知识堆积"型代码（黑名单、负向词、评分加分），改动很容易一边修好一边弄坏。
没有语料就只能靠"某次真机测试看起来没问题"来判断，而这类判断在过去已经反复出过回归
（如 ICU 下 \b 对中文失效、兔喜单段码被三段式码的子串干扰、金融闸门误杀餐饮取餐截图）。

## 采集一条语料

1. 真机触发一次识别（截图 / 分享 / 磁贴 / 短信）。
2. 设置页 →「关于」→「🔍 识别调试」。
3. 点右上角「📤 导出语料」——会把最近一次识别导出成文本并走系统分享面板。
4. 人工核对 \`E\` 段（导出的 \`E\` 是"当前行为"的快照，**必须人工确认真值**），补上
   \`E forbid\`（明确不该出现的串）与 \`# note:\`（说明场景来源）。
5. 存成 \`app/src/test/resources/corpus/<场景>-<序号>.txt\`。

## 格式

\`\`\`
# id: cainiao-3seg-ping        # 唯一 id（known-failures.txt 用它引用）
# source: screen               # screen / share / sms
# screen: 2400                 # 屏幕高度（0 = 无坐标，位置加分不生效）
# note: 一句话说明场景来源
# allow-extra: true            # 可选：允许出现未列出的码（默认 false，即严格集合比对）

L <x|-> <y|-> <w|-> <h|-> <conf|-> <OCR 行文本...>
E code <pickup_parcel|pickup_food|coupon> <码值>
E forbid <绝不应被当成码的串>
E address <全屏地址应包含的片段>
E station <站点名应包含的片段>
E cabinet <柜号（精确匹配）>
E from <应命中的地址步骤，如 S0-label / S6a / S0c-column>
E codeaddr <码值> <该码窗口地址应包含的片段>
\`\`\`

- 坐标为 \`-\` 表示该路径没有 boundingBox（分享/短信路径即如此，几何策略不参与）。
- **不写任何 \`E code\` = 期望识别不到任何码**（用于负向用例：裸数字、电量百分比、金融通知…）。
- 执行器会先过金融/支付闸门（\`isFinancialNoise\`），再跑 \`CodeExtractor.extract(context=null)\`
  ——不加载自学习规则、不写学习统计，保证确定性。

## 运行

\`\`\`bash
./gradlew :app:testDebugUnitTest --tests "com.pickupcode.app.corpus.CorpusRegressionTest"
\`\`\`

输出（见 \`build.gradle.kts\` 的 testLogging）：

\`\`\`
================ corpus 指标 ================
语料条数      : 25
整体通过      : 24/25
码 precision  : 1.0000  (TP=24 FP=0)
码 recall     : 1.0000  (FN=0)
地址命中      : 5/5
已知失败清单  : 1 条
=============================================
\`\`\`

## 已知失败清单

\`known-failures.txt\` 里列出的 id **失败不阻断 CI**，用于记录"已确认但尚未修"的缺陷。
若其中某条开始通过，测试会打印提示，请把它从清单移除——清单必须保持诚实。

## 一条语料失败时看什么

失败信息会同时给出期望与实际：

\`\`\`
addr-label-degraded (addr-label-degraded.txt) 未通过:
  - 地址不符：期望含[育新路北段爱玛电动车旁边]，实际=[育新路育新路育]
  实际码=1-6-5020(pickup_parcel)  地址=[育新路育新路育] from=S0b-addrLabel
\`\`\`

配合「识别调试」面板里的候选/分数列表，可以直接定位是哪条规则、哪个步骤出了问题。
