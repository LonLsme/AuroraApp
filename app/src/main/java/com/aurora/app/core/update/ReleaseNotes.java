package com.aurora.app.core.update;

import java.util.regex.Pattern;

/**
 * Release 说明的轻量 Markdown 清理。
 *
 * <p>GitHub Release 的 {@code body} 是 Markdown 原文，直接丢给 {@link android.widget.TextView}
 * 会看到一堆 {@code ##}、{@code **}、{@code <!-- -->}。这里只做「去标记」，
 * <b>不做渲染</b> —— 为一条更新日志引入一个 Markdown 解析库不值得，
 * 而用户真正想看的只是「改了什么」这几行字。
 *
 * <p>所有方法都是纯函数，行为由 {@code ReleaseNotesTest} 逐条锁定。
 */
public final class ReleaseNotes {

    /** HTML 注释（Release 模板里常用来写给自己看的备注，用户不该看到）。 */
    private static final Pattern HTML_COMMENT =
            Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** 图片 ![alt](url) —— 更新日志里的截图对纯文本界面没有意义，整段去掉。 */
    private static final Pattern IMAGE =
            Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

    /** 链接 [text](url) —— 保留文字，丢掉 URL（对话框里点了也跳不出去，留着只会占行）。 */
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    /** 行首标题井号：{@code ### 修复} → {@code 修复}。 */
    private static final Pattern HEADING =
            Pattern.compile("(?m)^[ \\t]*#{1,6}[ \\t]+");

    /** 行首引用符号：{@code > 说明} → {@code 说明}。 */
    private static final Pattern QUOTE =
            Pattern.compile("(?m)^[ \\t]*>[ \\t]?");

    /**
     * 裸 URL：{@code ... by @someone in https://github.com/x/pull/1} 这种。
     *
     * <p>GitHub 自动生成的 release notes 每条都带一个 PR 链接，在对话框里会折成两三行，
     * 而对话框里点不动它（TextView 不是 LinkMovementMethod）。整段去掉，留下标题即可。
     */
    private static final Pattern BARE_URL =
            Pattern.compile("https?://\\S+");

    /**
     * 任务列表项：{@code - [x] 已完成} → {@code · 已完成}。
     *
     * <p>必须独立成一条、并在 {@link #BULLET} <b>之前</b>处理：
     * 先把 {@code - } 换成 {@code · }，行首就不再是 {@code [x]}，
     * {@link #CHECKBOX} 的 {@code ^} 锚点会失效，结果会留下刺眼的 {@code · [x] 已完成}。
     */
    private static final Pattern TASK_ITEM =
            Pattern.compile("(?m)^[ \\t]*[-*+][ \\t]+\\[[ xX]\\][ \\t]*");

    /** 行首无序列表符号：{@code - 修复} → {@code · 修复}。 */
    private static final Pattern BULLET =
            Pattern.compile("(?m)^[ \\t]*[-*+][ \\t]+");

    /** 不带列表符号的勾选框：[ ] / [x] 直接出现在行首。 */
    private static final Pattern CHECKBOX =
            Pattern.compile("(?m)^\\[([ xX])\\][ \\t]*");

    /** 粗体 **x** / __x__。 */
    private static final Pattern BOLD =
            Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__", Pattern.DOTALL);

    /** 删除线 ~~x~~。 */
    private static final Pattern STRIKE =
            Pattern.compile("~~(.+?)~~", Pattern.DOTALL);

    /** 行内代码 `x` —— 去掉反引号，内容保留。 */
    private static final Pattern CODE =
            Pattern.compile("`([^`\n]*)`");

    /** 分隔线 --- / *** / ___ 独占一行。 */
    private static final Pattern HR =
            Pattern.compile("(?m)^[ \\t]*([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");

    /** 连续 3 个以上换行压成 2 个。 */
    private static final Pattern MANY_BLANK =
            Pattern.compile("\n{3,}");

    /** 行尾空白（删掉 URL 后常留下多余空格）。 */
    private static final Pattern TRAILING_SPACE =
            Pattern.compile("(?m)[ \\t]+$");

    private ReleaseNotes() {
    }

    /**
     * 把 Markdown 原文压成适合在对话框里直接显示的纯文本。
     *
     * @param markdown Release body，可为 null
     * @return 清理后的文本；入参为空时返回空串（<b>不是</b> null，调用方不必判空）
     */
    public static String plain(String markdown) {
        if (markdown == null) {
            return "";
        }
        String s = markdown.replace("\r\n", "\n").replace('\r', '\n');
        s = HTML_COMMENT.matcher(s).replaceAll("");
        s = IMAGE.matcher(s).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1");
        // 裸 URL 必须在 LINK 之后处理：LINK 处理过的链接已经没有 URL 了，
        // 反过来先删 URL 会把 [文字](url) 弄成 [文字]( ，链接规则就再也匹配不上
        s = BARE_URL.matcher(s).replaceAll("");
        s = HR.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceAll("");
        s = QUOTE.matcher(s).replaceAll("");
        s = TASK_ITEM.matcher(s).replaceAll("· ");
        s = BULLET.matcher(s).replaceAll("· ");
        s = CHECKBOX.matcher(s).replaceAll("");
        s = BOLD.matcher(s).replaceAll(m -> m.group(1) != null ? m.group(1) : m.group(2));
        s = STRIKE.matcher(s).replaceAll("$1");
        s = CODE.matcher(s).replaceAll("$1");
        s = TRAILING_SPACE.matcher(s).replaceAll("");
        s = MANY_BLANK.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    /**
     * 更新日志是否值得单独占一块区域。
     *
     * <p>清理后只剩空白、或只剩一两个字符时（有些 Release 只写了 tag 名），
     * 对话框应当直接跳过日志区，别留个空壳。
     */
    public static boolean hasContent(String markdown) {
        return plain(markdown).length() >= 2;
    }
}
