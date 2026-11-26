package com.aurora.app.ui.theme;

/**
 * 品牌配色生成器（<b>配色由开发者固定，App 内不提供任何自定义入口</b>）。
 *
 * <p>种子色为 {@link #SEED_COLOR}（Aurora 天空蓝），调色板配方固定为 TonalSpot +
 * 2025 色彩规范。想整体换肤就改这三个常量，UI 侧不需要任何改动 —— 也正因为如此，
 * 强调色 / 调色板风格 / 色彩规范这些维度已经从代码里彻底拿掉了。
 *
 * <p><b>为什么仍要生成而不是写死色值</b>：XML 层的主题属性只能来自静态资源，
 * 而 Compose 层（底部悬浮导航、主题页）需要一整套 {@code ColorScheme}。两边若各写一套
 * 常量，深色/浅色任一调整都会出现「底栏一种强调色、tab 指示线另一种」的割裂。
 * 由本类同时产出静态覆盖层（见 {@code res/values/aurora_palette.xml}）与运行时配色，
 * 单一数据源，改一处两边同步。
 *
 * <p><b>色彩空间</b>：使用 CIE Lab / LCh。L 通道即「色调 tone」（0 黑 ~ 100 白），
 * C 为彩度，h 为色相角。这与 HCT 的思路一致，且无需 CAM16 的复杂度。
 *
 * <p><b>色域</b>：{@link #colorFromLCh} 会自动把超出 sRGB 色域的彩度逐步收敛，保证输出可用。
 */
public final class AuroraPalette {

    /**
     * 品牌种子色（天空蓝，Material Blue 500 同色域，取自 Tailwind sky-500 #0EA5E9）。
     *
     * <p>强调色的其余槽位（primaryContainer / secondary / tertiary …）全部由它按 TonalSpot
     * 配方派生。改这一个值，XML 覆盖层与 Compose 配色会同时变化。
     */
    public static final int SEED_COLOR = 0xFF0EA5E9;

    private AuroraPalette() {
    }

    // ===================== 对外入口 =====================

    /**
     * 生成一套完整的 Material3 配色。
     *
     * @param dark 是否深色
     */
    public static Scheme generate(boolean dark) {
        return build(SEED_COLOR, dark);
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

    private static Scheme build(int seed, boolean dark) {
        double[] lch = rgbToLCh(seed);
        double seedHue = lch[2];
        double seedChroma = lch[1];

        // ---- TonalSpot：色相沿用种子色，彩度靠近种子（天空蓝在低彩度下会发灰） ----
        double primaryHue = seedHue;
        double primaryChroma = clamp(seedChroma * 0.9, 40, 56);
        double neutralChroma = 6;
        double neutralVariantChroma = 8;

        double tertiaryHue = wrapHue(primaryHue + 60);
        double tertiaryChroma = Math.max(primaryChroma * 0.85, primaryChroma > 0 ? 16 : 0);
        double secondaryChroma = primaryChroma * 0.5;

        // ---- 2025 规范：中性面与描边的色调表 ----
        double surfaceTone = dark ? 6 : 98;      // 页面底
        double containerTone = dark ? 17 : 92;   // 卡片
        double outlineTone = dark ? 70 : 40;     // 描边
        double outlineVariantTone = dark ? 35 : 82;

        Scheme s = new Scheme();
        // 主色系：浅色 tone 48、深色 tone 80
        // （Material3 默认浅色主色是 tone 40，但蓝色在 tone 40 会压成深钢蓝，
        //   与「天空蓝」的目标不符；48 仍满足白字 4.5:1 对比度）
        s.primary = colorFromLCh(dark ? 80 : 48, primaryChroma, primaryHue);
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

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
