package com.aurora.app.ui.theme;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 底栏效果三档的映射规则。
 *
 * <p>这段映射决定老用户升级后底栏长什么样：旧版是两个独立布尔，其中「玻璃开 + 模糊关」
 * 这种组合从未被 UI 表达过（玻璃本身含模糊），必须映射到液态玻璃而不是磨砂。
 * 一旦改错，用户的底栏会在没有任何操作的情况下变样，所以锁进单测。
 */
public class ThemeManagerBarEffectTest {

    @Test
    public void legacyGlassOnAlwaysMapsToGlass() {
        assertEquals(ThemeManager.BAR_EFFECT_GLASS,
                ThemeManager.migrateLegacyBarEffect(prefs(true, true)));
        assertEquals(ThemeManager.BAR_EFFECT_GLASS,
                ThemeManager.migrateLegacyBarEffect(prefs(true, false)));
    }

    @Test
    public void legacyGlassOffWithBlurIsFrosted() {
        assertEquals(ThemeManager.BAR_EFFECT_FROSTED,
                ThemeManager.migrateLegacyBarEffect(prefs(false, true)));
    }

    @Test
    public void legacyBothOffIsOpaque() {
        assertEquals(ThemeManager.BAR_EFFECT_OPAQUE,
                ThemeManager.migrateLegacyBarEffect(prefs(false, false)));
    }

    @Test
    public void noLegacyKeysMeansGlass() {
        // 全新安装：偏好里一个键都没有 → 沿用既有观感
        assertEquals(ThemeManager.BAR_EFFECT_GLASS,
                ThemeManager.migrateLegacyBarEffect(new FakePrefs()));
    }

    @Test
    public void outOfRangeFallsBackToDefault() {
        assertEquals(ThemeManager.BAR_EFFECT_GLASS, ThemeManager.normalizeBarEffect(-1));
        assertEquals(ThemeManager.BAR_EFFECT_GLASS, ThemeManager.normalizeBarEffect(3));
        assertEquals(ThemeManager.BAR_EFFECT_GLASS, ThemeManager.normalizeBarEffect(Integer.MAX_VALUE));
        assertEquals(ThemeManager.BAR_EFFECT_OPAQUE, ThemeManager.normalizeBarEffect(0));
        assertEquals(ThemeManager.BAR_EFFECT_FROSTED, ThemeManager.normalizeBarEffect(1));
        assertEquals(ThemeManager.BAR_EFFECT_GLASS, ThemeManager.normalizeBarEffect(2));
    }

    private static FakePrefs prefs(boolean glass, boolean blur) {
        FakePrefs p = new FakePrefs();
        p.values.put("liquid_glass", glass);
        p.values.put("bottom_bar_blur", blur);
        return p;
    }

    /** 只实现迁移路径用到的读接口；写入与监听不该被走到，走到就直接失败。 */
    private static final class FakePrefs implements SharedPreferences {

        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            throw new UnsupportedOperationException("未使用");
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            throw new UnsupportedOperationException("迁移路径不写入");
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            throw new UnsupportedOperationException("未使用");
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            throw new UnsupportedOperationException("未使用");
        }
    }
}
