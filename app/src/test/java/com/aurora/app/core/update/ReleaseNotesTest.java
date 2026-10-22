package com.aurora.app.core.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ReleaseNotes} 的单元测试（纯 JVM）。
 *
 * <p>这类「文本清理」最容易在边界上出错：漏删注释会把作者写给自己的备注显示给用户，
 * 误删则会把正常的中文更新日志吃掉。所以逐条锁死行为。
 */
public class ReleaseNotesTest {

    @Test
    public void headingsAreRemoved() {
        assertEquals("修复内容", ReleaseNotes.plain("## 修复内容"));
        assertEquals("标题", ReleaseNotes.plain("### 标题"));
        // 井号不在行首时是普通字符，不能动
        assertEquals("C# 相关", ReleaseNotes.plain("C# 相关"));
    }

    @Test
    public void bulletsBecomeMiddleDots() {
        assertEquals("· 修复闪退\n· 新增主题",
                ReleaseNotes.plain("- 修复闪退\n- 新增主题"));
        assertEquals("· 一\n· 二", ReleaseNotes.plain("* 一\n* 二"));
    }

    @Test
    public void emphasisMarkersAreStripped() {
        assertEquals("修复 闪退 问题",
                ReleaseNotes.plain("修复 **闪退** 问题"));
        assertEquals("加粗", ReleaseNotes.plain("__加粗__"));
        assertEquals("删除", ReleaseNotes.plain("~~删除~~"));
        assertEquals("代码片段", ReleaseNotes.plain("`代码片段`"));
    }

    @Test
    public void linksKeepTextAndDropUrl() {
        assertEquals("查看文档", ReleaseNotes.plain("[查看文档](https://example.com/doc)"));
    }

    @Test
    public void bareUrlsAreRemoved() {
        // GitHub 自动生成的 release notes 形态：每条都带 PR 链接
        assertEquals("· 修复闪退 by @someone in",
                ReleaseNotes.plain("* 修复闪退 by @someone in https://github.com/x/y/pull/1"));
        // 删掉 URL 后行尾不能残留空格
        assertEquals("说明", ReleaseNotes.plain("说明 https://example.com/a"));
    }

    @Test
    public void imagesAreRemovedEntirely() {
        assertEquals("说明", ReleaseNotes.plain("![截图](https://example.com/a.png)\n说明"));
    }

    @Test
    public void htmlCommentsAreRemovedEvenMultiline() {
        String raw = "正文前\n<!--\n  这是写给维护者看的备注\n  不该显示给用户\n-->\n正文后";
        String out = ReleaseNotes.plain(raw);
        assertFalse(out.contains("维护者"));
        assertTrue(out.contains("正文前"));
        assertTrue(out.contains("正文后"));
    }

    @Test
    public void horizontalRulesAndQuotesAreRemoved() {
        assertEquals("说明", ReleaseNotes.plain("---\n说明"));
        assertEquals("引用", ReleaseNotes.plain("> 引用"));
    }

    @Test
    public void taskListCheckboxesAreRemoved() {
        assertEquals("· 已完成\n· 待办",
                ReleaseNotes.plain("- [x] 已完成\n- [ ] 待办"));
    }

    @Test
    public void excessiveBlankLinesCollapse() {
        assertEquals("A\n\nB", ReleaseNotes.plain("A\n\n\n\n\nB"));
    }

    @Test
    public void windowsLineEndingsAreNormalized() {
        assertEquals("· 一\n· 二", ReleaseNotes.plain("- 一\r\n- 二"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", ReleaseNotes.plain(null));
        assertEquals("", ReleaseNotes.plain(""));
        assertEquals("", ReleaseNotes.plain("   \n\t\n "));
    }

    @Test
    public void hasContentRejectsEmptyAndTiny() {
        assertFalse(ReleaseNotes.hasContent(null));
        assertFalse(ReleaseNotes.hasContent(""));
        // 只剩一个字符时不值得单独占一块区域
        assertFalse(ReleaseNotes.hasContent("x"));
        // 只有标记、清理后什么都不剩
        assertFalse(ReleaseNotes.hasContent("<!-- 内部备注 -->"));
        assertTrue(ReleaseNotes.hasContent("修复闪退"));
    }
}
