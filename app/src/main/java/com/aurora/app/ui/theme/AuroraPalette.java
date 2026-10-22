package com.aurora.app.ui.theme;

/**
 * 调色板引擎（纯 Java，零 Android 依赖，参考 materialkolor / Material Color Utilities 的
 * DynamicScheme 思路，按 AuroraApp 的 Java + XML View 架构落地）。
 *
 * <p><b>为什么需要它</b>：KernelSU Manager 是 100% Compose，颜色由 materialkolor 在运行时
 * 从「种子色 + PaletteStyle + ColorSpec」现算。AuroraApp 的主 UI 是 XML View，
 * 主题属性必须在资源期确定，无法在运行时注入任意色值。因此这里的做法是：
 * <ol>
 *   <li>在本类中实现完整的调色板算法（色相/彩度/明度三维独立可控）；</li>
 *   <li>运行时用它给 Compose 层（底部导航、主题页、预览卡）生成完整配色；</li>
 *   <li>用 {@link #main(String[])} 在<b>构建前</b>把 16 键 × 9 风格的强调色预生成成
 *       {@code ThemeOverlay} 资源，供 XML 层按索引叠加 —— 两者同源，保证 Compose 与 XML 配色一致。</li>
 * </ol>
 *
 * <p><b>色彩空间</b>：使用 CIE Lab / LCh。L 通道即「色调 tone」（0 黑 ~ 100 白），
 * C 为彩度，h 为色相角。这与 HCT 的思路一致，且无需 CAM16 的复杂度。
 *
 * <p><b>色域</b>：{@link #colorFromLCh} 会自动把超出 sRGB 色域的彩度逐步收敛，保证输出可用。
 */
public final class AuroraPalette {

    // ===================== 种子色（KernelSU keyColorOptions 全表 + 默认色） =====================

    /** 强调色可选数量：0 = 默认（品牌绿），1..15 = KernelSU 预设色板。 */
    public static final int KEY_COUNT = 16;

    /** 种子色表。索引 0 为 AuroraApp 品牌绿（保持既有观感），其余对齐 KernelSU Manager。 */
    public static final int[] SEED_COLORS = {
            0xFF00C853, // 0  默认 · 品牌绿
            0xFFF44336, // 1  红
            0xFFE91E63, // 2  玫红
            0xFF9C27B0, // 3  紫
            0xFF673AB7, // 4  深紫
            0xFF3F51B5, // 5  靛蓝
            0xFF2196F3, // 6  蓝
            0xFF00BCD4, // 7  青
            0xFF009688, // 8  蓝绿
            0xFF4FAF50, // 9  绿
            0xFFFFEB3B, // 10 黄
            0xFFFFC107, // 11 琥珀
            0xFFFF9800, // 12 橙
            0xFF795548, // 13 棕
            0xFF607D8F, // 14 灰蓝
            0xFFFF9CA8, // 15 粉
    };

    // ===================== 调色板风格（对应 materialkolor PaletteStyle） =====================

    public static final int STYLE_TONAL_SPOT = 0;
    public static final int STYLE_NEUTRAL = 1;
    public static final int STYLE_VIBRANT = 2;
    public static final int STYLE_EXPRESSIVE = 3;
    public static final int STYLE_FIDELITY = 4;
    public static final int STYLE_CONTENT = 5;
    public static final int STYLE_MONOCHROME = 6;
    public static final int STYLE_RAINBOW = 7;
    public static final int STYLE_FRUIT_SALAD = 8;
    public static final int STYLE_COUNT = 9;

    public static final String[] STYLE_NAMES = {
            "TonalSpot", "Neutral", "Vibrant", "Expressive", "Fidelity",
            "Content", "Monochrome", "Rainbow", "FruitSalad",
    };

    /** 风格中文说明（主题页下拉里展示，帮助用户理解差异）。 */
    public static final String[] STYLE_LABELS = {
            "柔和（默认）", "中性灰", "鲜艳", "表现力", "忠实种子色",
            "内容色", "单色", "彩虹", "水果沙拉",
    };

    // ===================== 色彩规范（对应 materialkolor ColorSpec.SpecVersion） =====================

    public static final int SPEC_2021 = 0;
    public static final int SPEC_2025 = 1;
    public static final int SPEC_COUNT = 2;

    public static final String[] SPEC_NAMES = {"SPEC_2021", "SPEC_2025"};
    public static final String[] SPEC_LABELS = {"2021 规范", "2025 规范"};

    private AuroraPalette() {
    }

    // ===================== 对外入口 =====================

    /**
     * 生成一套完整的 Material3 配色。
     *
     * @param keyIndex   种子色索引（0..{@link #KEY_COUNT}-1）
     * @param styleIndex 调色板风格（0..{@link #STYLE_COUNT}-1）
     * @param specIndex  色彩规范（{@link #SPEC_2021} / {@link #SPEC_2025}）
     * @param dark       是否深色
     */
    public static Scheme generate(int keyIndex, int styleIndex, int specIndex, boolean dark) {
        int seed = SEED_COLORS[clamp(keyIndex, 0, KEY_COUNT - 1)];
        int style = clamp(styleIndex, 0, STYLE_COUNT - 1);
        int spec = clamp(specIndex, 0, SPEC_COUNT - 1);
        return build(seed, style, spec, dark);
    }

    /** 强调色（primary）的 ARGB 值：XML 覆盖层与 Compose 都用它，保证两侧一致。 */
    public static int primaryOf(int keyIndex, int styleIndex, int specIndex, boolean dark) {
        return generate(keyIndex, styleIndex, specIndex, dark).primary;
    }

    // ===================== 核心：调色板构造 =====================

    /** 一套配色（仅含应用实际需要的槽位）。 */
    public static final class Scheme {
        public int primary;
        public int onPrimary;
        public int primaryContainer;
        public int onPrimaryContainer;
        public int secondary;
        public int secondaryContainer;
        public int onSecondaryContainer;
        public int tertiary;
        public int tertiaryContainer;
        public int onTertiaryContainer;
        public int error;
        public int errorContainer;
        public int onErrorContainer;
        public int background;
        public int onBackground;
        public int surface;
        public int surfaceContainerLowest;
        public int surfaceContainerLow;
        public int surfaceContainer;
        public int surfaceContainerHigh;
        public int surfaceContainerHighest;
        public int onSurface;
        public int surfaceVariant;
        public int onSurfaceVariant;
        public int outline;
        public int outlineVariant;
        public int inverseSurface;
        public int inverseOnSurface;
        public int inversePrimary;
    }

    private static Scheme build(int seed, int style, int spec, boolean dark) {
        double[] lch = rgbToLCh(seed);
        double seedHue = lch[2];
        double seedChroma = lch[1];

        // ---- 由风格决定：主色相 / 主彩度 / 中性彩度 ----
        double primaryHue;
        double primaryChroma;
        double neutralChroma;
        double neutralVariantChroma;

        switch (style) {
            case STYLE_NEUTRAL:
                primaryHue = seedHue;
                primaryChroma = 6;
                neutralChroma = 2;
                neutralVariantChroma = 2;
                break;
            case STYLE_VIBRANT:
                primaryHue = seedHue;
                primaryChroma = clamp(seedChroma * 1.8, 48, 100);
                neutralChroma = 18;
                neutralVariantChroma = 18;
                break;
            case STYLE_EXPRESSIVE:
                primaryHue = wrapHue(seedHue + 240);
                primaryChroma = clamp(seedChroma * 0.6, 24, 48);
                neutralChroma = 6;
                neutralVariantChroma = 8;
                break;
            case STYLE_FIDELITY:
                primaryHue = seedHue;
                primaryChroma = clamp(seedChroma * 1.15, 0, 100);
                neutralChroma = 6;
                neutralVariantChroma = 8;
                break;
            case STYLE_CONTENT:
                primaryHue = seedHue;
                primaryChroma = clamp(seedChroma, 0, 100);
                neutralChroma = 6;
                neutralVariantChroma = 8;
                break;
            case STYLE_MONOCHROME:
                primaryHue = seedHue;
                primaryChroma = 0;
                neutralChroma = 0;
                neutralVariantChroma = 0;
                break;
            case STYLE_RAINBOW:
                primaryHue = seedHue;
                primaryChroma = 48;
                neutralChroma = 16;
                neutralVariantChroma = 16;
                break;
            case STYLE_FRUIT_SALAD:
                primaryHue = wrapHue(seedHue - 50);
                primaryChroma = 48;
                neutralChroma = 16;
                neutralVariantChroma = 16;
                break;
            case STYLE_TONAL_SPOT:
            default:
                primaryHue = seedHue;
                // 规格为 36；这里与种子彩度混合，避免高饱和种子被“洗灰”
                primaryChroma = clamp(seedChroma * 0.55, 24, 48);
                neutralChroma = 6;
                neutralVariantChroma = 8;
                break;
        }

        double tertiaryHue = wrapHue(primaryHue + 60);
        double tertiaryChroma = Math.max(primaryChroma * 0.85, primaryChroma > 0 ? 16 : 0);
        double secondaryChroma = primaryChroma * 0.5;

        // ---- 由规范决定：中性面与描边的色调表 ----
        double surfaceTone;      // 页面底
        double containerTone;    // 卡片
        double outlineTone;      // 描边
        double outlineVariantTone;
        if (spec == SPEC_2025) {
            surfaceTone = dark ? 6 : 98;
            containerTone = dark ? 17 : 92;
            outlineTone = dark ? 70 : 40;
            outlineVariantTone = dark ? 35 : 82;
        } else {
            surfaceTone = dark ? 10 : 99;
            containerTone = dark ? 12 : 94;
            outlineTone = dark ? 60 : 50;
            outlineVariantTone = dark ? 30 : 80;
        }

        Scheme s = new Scheme();
        // 主色系：浅色用 tone 40、深色用 tone 80（Material3 规范）
        s.primary = colorFromLCh(dark ? 80 : 40, primaryChroma, primaryHue);
        s.onPrimary = colorFromLCh(dark ? 20 : 100, primaryChroma * 0.4, primaryHue);
        s.primaryContainer = colorFromLCh(dark ? 30 : 90, primaryChroma * 0.75, primaryHue);
        s.onPrimaryContainer = colorFromLCh(dark ? 90 : 10, primaryChroma * 0.6, primaryHue);

        s.secondary = colorFromLCh(dark ? 80 : 40, secondaryChroma, primaryHue);
        s.secondaryContainer = colorFromLCh(dark ? 30 : 90, secondaryChroma * 0.75, primaryHue);
        s.onSecondaryContainer = colorFromLCh(dark ? 90 : 10, secondaryChroma * 0.6, primaryHue);

        s.tertiary = colorFromLCh(dark ? 80 : 40, tertiaryChroma, tertiaryHue);
        s.tertiaryContainer = colorFromLCh(dark ? 30 : 90, tertiaryChroma * 0.75, tertiaryHue);
        s.onTertiaryContainer = colorFromLCh(dark ? 90 : 10, tertiaryChroma * 0.6, tertiaryHue);

        // 错误色：固定色相 25、高彩度
        s.error = colorFromLCh(dark ? 80 : 40, 84, 25);
        s.errorContainer = colorFromLCh(dark ? 30 : 90, 60, 25);
        s.onErrorContainer = colorFromLCh(dark ? 90 : 10, 48, 25);

        // 中性面
        s.background = colorFromLCh(surfaceTone, neutralChroma, primaryHue);
        s.onBackground = colorFromLCh(dark ? 90 : 10, neutralChroma, primaryHue);
        s.surface = s.background;
        s.surfaceContainerLowest = colorFromLCh(dark ? 4 : 100, neutralChroma, primaryHue);
        s.surfaceContainerLow = colorFromLCh(dark ? 10 : 96, neutralChroma, primaryHue);
        s.surfaceContainer = colorFromLCh(containerTone, neutralChroma, primaryHue);
        s.surfaceContainerHigh = colorFromLCh(dark ? 22 : 90, neutralChroma, primaryHue);
        s.surfaceContainerHighest = colorFromLCh(dark ? 24 : 87, neutralChroma, primaryHue);
        s.onSurface = colorFromLCh(dark ? 90 : 10, neutralChroma, primaryHue);

        s.surfaceVariant = colorFromLCh(dark ? 30 : 90, neutralVariantChroma, primaryHue);
        s.onSurfaceVariant = colorFromLCh(dark ? 80 : 30, neutralVariantChroma, primaryHue);
        s.outline = colorFromLCh(outlineTone, neutralVariantChroma, primaryHue);
        s.outlineVariant = colorFromLCh(outlineVariantTone, neutralVariantChroma, primaryHue);

        s.inverseSurface = colorFromLCh(dark ? 90 : 20, neutralChroma, primaryHue);
        s.inverseOnSurface = colorFromLCh(dark ? 20 : 95, neutralChroma, primaryHue);
        s.inversePrimary = colorFromLCh(dark ? 40 : 80, primaryChroma, primaryHue);
        return s;
    }

    // ===================== 色彩空间换算 =====================

    /** Lab 白点 D65。 */
    private static final double[] WHITE = {0.95047, 1.00000, 1.08883};

    private static final double DELTA = 6.0 / 29.0;
    private static final double DELTA3 = DELTA * DELTA * DELTA;

    /** LCh -> ARGB；彩度超出 sRGB 色域时自动收敛。 */
    public static int colorFromLCh(double lStar, double chroma, double hueDeg) {
        double c = Math.max(0, chroma);
        for (int i = 0; i < 48 && c > 0.25; i++) {
            int argb = lchToArgb(lStar, c, hueDeg);
            if (argb != 0) {
                return argb;
            }
            c *= 0.94;
        }
        int argb = lchToArgb(lStar, 0, hueDeg);
        // 极端情况（亮度已超出可显示范围）兜底为黑或白
        return argb != 0 ? argb : (lStar >= 50 ? 0xFFFFFFFF : 0xFF000000);
    }

    /** tone + chroma + hue -> ARGB；越界返回 0。 */
    private static int lchToArgb(double lStar, double chroma, double hueDeg) {
        double rad = Math.toRadians(hueDeg);
        double a = chroma * Math.cos(rad);
        double b = chroma * Math.sin(rad);

        double fy = (lStar + 16.0) / 116.0;
        double fx = fy + a / 500.0;
        double fz = fy - b / 200.0;

        double x = WHITE[0] * finv(fx);
        double y = WHITE[1] * finv(fy);
        double z = WHITE[2] * finv(fz);

        double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;

        if (r < -0.0005 || r > 1.0005 || g < -0.0005 || g > 1.0005 || bl < -0.0005 || bl > 1.0005) {
            return 0; // 超出 sRGB 色域
        }
        return 0xFF000000
                | (srgbEncode(r) << 16)
                | (srgbEncode(g) << 8)
                | srgbEncode(bl);
    }

    /** ARGB -> {L, C, h}。 */
    public static double[] rgbToLCh(int argb) {
        double r = srgbDecode((argb >> 16) & 0xFF);
        double g = srgbDecode((argb >> 8) & 0xFF);
        double b = srgbDecode(argb & 0xFF);

        double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;

        double fx = f(x / WHITE[0]);
        double fy = f(y / WHITE[1]);
        double fz = f(z / WHITE[2]);

        double l = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double bb = 200.0 * (fy - fz);

        double chroma = Math.sqrt(a * a + bb * bb);
        double hue = Math.toDegrees(Math.atan2(bb, a));
        if (hue < 0) {
            hue += 360.0;
        }
        return new double[]{l, chroma, hue};
    }

    private static double f(double t) {
        return t > DELTA3 ? Math.cbrt(t) : t / (3 * DELTA * DELTA) + 4.0 / 29.0;
    }

    private static double finv(double t) {
        return t > DELTA ? t * t * t : 3 * DELTA * DELTA * (t - 4.0 / 29.0);
    }

    private static double srgbDecode(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int srgbEncode(double c) {
        double v = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255.0);
    }

    private static double wrapHue(double hue) {
        double h = hue % 360.0;
        return h < 0 ? h + 360.0 : h;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ===================== 代码生成：把色调板固化成资源覆盖层 =====================

    /**
     * 生成器入口。用法：
     * <pre>
     *   javac -d out AuroraPalette.java
     *   java -cp out com.aurora.app.ui.theme.AuroraPalette &lt;resDir&gt; &lt;javaOutDir&gt;
     * </pre>
     * 会在 {@code values/}、{@code values-night/} 写入 {@code aurora_accent_overlays.xml}，
     * 并在 {@code javaOutDir} 写入 {@code AccentOverlays.java}。
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: AuroraPalette <resDir> <javaOutDir>");
            System.exit(1);
            return;
        }
        java.io.File resDir = new java.io.File(args[0]);
        java.io.File javaOut = new java.io.File(args[1]);

        writeOverlayXml(new java.io.File(resDir, "values/aurora_accent_overlays.xml"), false);
        writeOverlayXml(new java.io.File(resDir, "values-night/aurora_accent_overlays.xml"), true);
        writeJavaTable(new java.io.File(javaOut, "AccentOverlays.java"));
        System.out.println("已生成强调色覆盖层："
                + (KEY_COUNT - 1) + " 键 × " + STYLE_COUNT + " 风格（day/night 各一套）");
    }

    private static void writeOverlayXml(java.io.File file, boolean dark) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<!--\n");
        sb.append("  自动生成，请勿手改。重新生成：\n");
        sb.append("    javac -d out AuroraPalette.java\n");
        sb.append("    java -cp out com.aurora.app.ui.theme.AuroraPalette app/src/main/res app/src/main/java/com/aurora/app/ui/theme\n");
        sb.append("  内容：种子色 × 调色板风格 的强调色覆盖层（").append(dark ? "深色" : "浅色").append("）。\n");
        sb.append("-->\n<resources>\n");
        for (int k = 0; k < KEY_COUNT; k++) {
            for (int st = 0; st < STYLE_COUNT; st++) {
                // 覆盖层只固化浅色/深色的 primary 与 onPrimary；
                // 色彩规范不影响 primary，故资源表不必再乘 SPEC 维度。
                Scheme s = build(SEED_COLORS[k], st, SPEC_2021, dark);
                sb.append("    <style name=\"ThemeOverlay.Aurora.Accent.").append(k).append('_').append(st)
                        .append("\" parent=\"\">\n");
                sb.append("        <item name=\"auroraAccent\">").append(hex(s.primary)).append("</item>\n");
                sb.append("        <item name=\"auroraOnAccent\">").append(hex(s.onPrimary)).append("</item>\n");
                sb.append("    </style>\n");
            }
        }
        sb.append("</resources>\n");
        write(file, sb.toString());
    }

    private static void writeJavaTable(java.io.File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.aurora.app.ui.theme;\n\n");
        sb.append("import com.aurora.app.R;\n\n");
        sb.append("/**\n");
        sb.append(" * 自动生成的强调色覆盖层索引表，请勿手改。\n");
        sb.append(" *\n");
        sb.append(" * <p>维度：{@code TABLE[强调色索引][调色板风格]}，值为对应 {@code ThemeOverlay} 的 style 资源 id。\n");
        sb.append(" * 由 {@link AuroraPalette#main(String[])} 生成，与资源文件同源。\n");
        sb.append(" */\n");
        sb.append("public final class AccentOverlays {\n\n");
        sb.append("    private AccentOverlays() {\n    }\n\n");
        sb.append("    public static final int[][] TABLE = {\n");
        for (int k = 0; k < KEY_COUNT; k++) {
            sb.append("            {");
            for (int st = 0; st < STYLE_COUNT; st++) {
                if (st > 0) {
                    sb.append(", ");
                }
                sb.append("R.style.ThemeOverlay_Aurora_Accent_").append(k).append('_').append(st);
            }
            sb.append("},\n");
        }
        sb.append("    };\n\n");
        sb.append("    /** 取覆盖层 id；索引越界时回退到默认风格。 */\n");
        sb.append("    public static int of(int keyIndex, int styleIndex) {\n");
        sb.append("        int k = Math.max(0, Math.min(TABLE.length - 1, keyIndex));\n");
        sb.append("        int st = Math.max(0, Math.min(TABLE[k].length - 1, styleIndex));\n");
        sb.append("        return TABLE[k][st];\n");
        sb.append("    }\n");
        sb.append("}\n");
        write(file, sb.toString());
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }

    private static void write(java.io.File file, String content) throws Exception {
        java.io.File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
