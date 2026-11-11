package org.example.aispingboot.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪与危机识别器（规则驱动，不依赖大模型）。
 *
 * <p>为什么用关键词规则而不是让模型判断：</p>
 * <ul>
 *   <li>确定性：同样的输入永远得到同样的风险等级，可测试、可复现；</li>
 *   <li>及时性：同步毫秒级返回，不用等模型生成完才决定是否要兜底；</li>
 *   <li>兜底可靠性：模型输出不可控，可能漏掉热线，规则层不会漏。</li>
 * </ul>
 *
 * <p>风险分级与前端约定一致：0 正常 / 1 关注 / 2 预警 / 3 危机。</p>
 */
public final class EmotionAnalyzer {

    private EmotionAnalyzer() {
    }

    /** 危机级关键词：出现即判定为风险 3，必须在回复中强制给出血线与热线。 */
    private static final List<String> CRISIS_WORDS = List.of(
            "自杀", "自尽", "轻生", "不想活", "活不下去", "活着没意思", "活着没意义",
            "想死", "死了算了", "结束生命", "结束自己", "割腕", "跳楼", "跳河",
            "上吊", "服毒", "安眠药", "遗书", "自残", "伤害自己"
    );

    /** 预警级关键词：风险 2，需要主动关怀并给出求助渠道。 */
    private static final List<String> WARNING_WORDS = List.of(
            "绝望", "撑不下去", "扛不住", "崩溃", "每天都在哭", "整夜睡不着",
            "没有人需要我", "没有意义", "想消失", "不想醒来"
    );

    /** 关注级关键词：风险 1，属于常见负性情绪表达。 */
    private static final List<String> CONCERN_WORDS = List.of(
            "压力", "焦虑", "失眠", "难过", "孤独", "烦躁", "委屈", "迷茫",
            "疲惫", "抑郁", "紧张", "害怕", "心慌", "内耗", "emo"
    );

    /** 否定词：出现在关键词前两个字内时视为否定表达（如「我不想死」），不计入风险。 */
    private static final List<String> NEGATIONS = List.of("不", "没", "别", "无", "未");

    /** 情绪 → 关键词映射，用于识别主导情绪（与前端标签用词保持一致）。 */
    private static final Map<String, List<String>> EMOTION_WORDS = new LinkedHashMap<>();

    static {
        EMOTION_WORDS.put("焦虑", List.of("焦虑", "紧张", "心慌", "担心", "不安", "压力"));
        EMOTION_WORDS.put("悲伤", List.of("悲伤", "难过", "伤心", "哭", "失落"));
        EMOTION_WORDS.put("愤怒", List.of("愤怒", "生气", "火大", "气死", "恼火", "烦死"));
        EMOTION_WORDS.put("恐惧", List.of("恐惧", "害怕", "恐怖", "吓人", "不敢"));
        EMOTION_WORDS.put("沮丧", List.of("沮丧", "无力", "颓废", "摆烂", "提不起劲", "没动力"));
        EMOTION_WORDS.put("压力", List.of("压力", "考试", "找工作", "实习", "绩点", "论文", "内耗"));
        EMOTION_WORDS.put("平静", List.of("平静", "还好", "一般般", "普通", "稳定"));
        EMOTION_WORDS.put("快乐", List.of("开心", "快乐", "高兴", "顺利", "满足", "幸福"));
    }

    /** 各情绪对应的温和建议。 */
    private static final Map<String, String> SUGGESTIONS = Map.of(
            "焦虑", "先把担心的事写下来，把「能控制的」和「控制不了的」分开看",
            "悲伤", "允许自己难过一会儿，找个信任的人说说，不用急着振作",
            "愤怒", "先离开让你生气的场景，等身体冷静下来再处理问题本身",
            "恐惧", "把害怕的事情拆成最小的一步，先做那一步就好",
            "沮丧", "今天只定一件小事，完成后就算赢，不用一次改变所有",
            "压力", "把任务按「必须今天做」和「可以晚点做」分两堆，先清第一堆",
            "平静", "状态不错，适合做点稍微有挑战的事",
            "快乐", "把这份好心情记下来，情绪低落时可以翻出来看",
            "中性", "可以先从记录今天发生的三件小事开始"
    );

    /** 各情绪对应的可执行小行动。 */
    private static final Map<String, List<String>> ACTIONS = Map.of(
            "焦虑", List.of("4-7-8 呼吸法做 3 轮", "把担心的事写成清单", "出门快走 15 分钟"),
            "悲伤", List.of("给朋友发一条消息", "听一首喜欢的歌", "写三行今天的感受"),
            "愤怒", List.of("离开现场喝杯水", "把想说的话先写在备忘录里", "做 20 个深呼吸"),
            "恐惧", List.of("把最坏结果写出来", "找一个可以陪你的人", "先完成最小的一步"),
            "沮丧", List.of("晒 10 分钟太阳", "整理桌面", "完成一件 5 分钟能做完的事"),
            "压力", List.of("列任务清单并排优先级", "番茄钟专注 25 分钟", "给自己留 30 分钟放空"),
            "平静", List.of("记录今天的小确幸", "做一件一直想做的事"),
            "快乐", List.of("把开心的事分享出去", "拍一张今天的照片"),
            "中性", List.of("记录今天的三件小事", "早睡 30 分钟")
    );

    /**
     * 24 小时心理援助热线。
     * 危机兜底文案直接使用这里的数据，保证号码只在一处维护。
     */
    public static final List<String> HOTLINES = List.of(
            "全国心理援助热线：12356",
            "北京心理危机研究与干预中心：010-82951332",
            "希望 24 热线：400-161-9995"
    );

    /**
     * 分析结果。
     *
     * @param primaryEmotion         主导情绪
     * @param emotionScore           情绪强度 0-100，越高代表负性情绪越强
     * @param isNegative             是否为负性情绪
     * @param riskLevel              风险等级 0-3
     * @param matchedKeywords        命中的风险关键词，便于排查误判
     * @param suggestion             温和建议
     * @param improvementSuggestions 可执行的小行动
     */
    public record EmotionResult(String primaryEmotion,
                                int emotionScore,
                                boolean isNegative,
                                int riskLevel,
                                List<String> matchedKeywords,
                                String suggestion,
                                List<String> improvementSuggestions) {
    }

    /**
     * 对一段文本做情绪与风险分析。
     *
     * @param text 用户输入，可为 null
     * @return 分析结果，text 为空时返回「中性 / 风险 0」
     */
    public static EmotionResult analyze(String text) {
        if (text == null || text.isBlank()) {
            return new EmotionResult("中性", 20, false, 0, List.of(),
                    SUGGESTIONS.get("中性"), ACTIONS.get("中性"));
        }

        // 按等级从高到低检测，风险等级取最高命中级别
        List<String> hits = new ArrayList<>();
        int riskLevel = 0;

        List<String> crisisHits = hitWords(text, CRISIS_WORDS);
        if (!crisisHits.isEmpty()) {
            riskLevel = 3;
            hits.addAll(crisisHits);
        }
        List<String> warningHits = hitWords(text, WARNING_WORDS);
        if (!warningHits.isEmpty() && riskLevel < 2) {
            riskLevel = 2;
        }
        hits.addAll(warningHits);

        List<String> concernHits = hitWords(text, CONCERN_WORDS);
        if (!concernHits.isEmpty() && riskLevel < 1) {
            riskLevel = 1;
        }
        hits.addAll(concernHits);

        // 命中词数量做小幅加权，避免单个关键词就把分数拉到接近满分
        int base = switch (riskLevel) {
            case 3 -> 92;
            case 2 -> 78;
            case 1 -> 58;
            default -> 25;
        };
        int score = Math.min(100, base + Math.max(0, hits.size() - 1) * 4);

        String emotion = detectEmotion(text, riskLevel);
        boolean negative = riskLevel >= 1 || !List.of("平静", "快乐", "中性").contains(emotion);

        return new EmotionResult(
                emotion,
                score,
                negative,
                riskLevel,
                hits,
                SUGGESTIONS.getOrDefault(emotion, SUGGESTIONS.get("中性")),
                ACTIONS.getOrDefault(emotion, ACTIONS.get("中性"))
        );
    }

    /**
     * 命中检测。
     * 关键词前两个字内出现否定词时跳过，避免「我不想死」被误判成危机。
     */
    private static List<String> hitWords(String text, List<String> words) {
        List<String> hits = new ArrayList<>();
        for (String w : words) {
            int idx = text.indexOf(w);
            while (idx >= 0) {
                if (!isNegated(text, idx)) {
                    hits.add(w);
                    break;
                }
                idx = text.indexOf(w, idx + 1);
            }
        }
        return hits;
    }

    /** 判断关键词前两个字内是否存在否定词。 */
    private static boolean isNegated(String text, int idx) {
        int from = Math.max(0, idx - 2);
        String prefix = text.substring(from, idx);
        return NEGATIONS.stream().anyMatch(prefix::contains);
    }

    /** 取命中次数最多的情绪，没有命中时按风险等级保守回落。 */
    private static String detectEmotion(String text, int riskLevel) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, List<String>> e : EMOTION_WORDS.entrySet()) {
            int c = 0;
            for (String w : e.getValue()) {
                if (text.contains(w)) {
                    c++;
                }
            }
            if (c > bestCount) {
                bestCount = c;
                best = e.getKey();
            }
        }
        if (best != null) {
            return best;
        }
        return switch (riskLevel) {
            case 3, 2 -> "悲伤";
            case 1 -> "压力";
            default -> "中性";
        };
    }

    /**
     * 危机兜底文案：只要命中风险，无论模型怎么回复都会追加在回复末尾。
     * 使用 Markdown，前端 MarkdownRenderer 可直接渲染。
     */
    public static String buildCrisisAppendix() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n\n");
        sb.append("**如果你现在有伤害自己的想法，请立刻联系专业帮助（24 小时在线）：**\n\n");
        for (String h : HOTLINES) {
            sb.append("- ").append(h).append("\n");
        }
        sb.append("- 情况紧急请直接拨打 **120**，或前往最近的医院急诊\n\n");
        sb.append("你不需要一个人扛着，愿意说出来本身就很勇敢。");
        return sb.toString();
    }

    /**
     * 风险等级的说明文案，供前端情绪面板的「温馨提示」卡片展示。
     *
     * @param riskLevel 风险等级 0-3
     * @return 对应文案
     */
    public static String riskDescription(int riskLevel) {
        return switch (riskLevel) {
            case 3 -> "你的状态让我们很担心。请立即联系学校心理中心，或拨打 24 小时热线："
                    + "12356 / 010-82951332 / 400-161-9995；情况紧急请拨打 120。";
            case 2 -> "你最近承受的压力比较大，建议尽快和信任的人聊聊，也可以拨打 12356 心理援助热线。";
            case 1 -> "你的情绪需要多一点关照，试试下面的小行动，给自己留一点喘息的空间。";
            default -> "目前状态比较平稳，继续保持规律作息和自我关照。";
        };
    }
}
