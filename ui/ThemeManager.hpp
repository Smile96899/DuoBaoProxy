#pragma once

#include <functional>

#include <QString>
#include <QPalette>

// 主题管理：只有两套品牌主题，"0" 浅色、"1" 深色。
class ThemeManager {
public:
    QString current_theme = "";

    // 主题切换后的回调（主窗口用它刷新图标）
    std::function<void()> on_theme_changed;

    void ApplyTheme(const QString &theme);

    [[nodiscard]] bool IsDark() const { return current_theme == "1"; }

    // 根据当前主题返回图标资源路径，例如 IconPath("h-nodes") -> ":/icon/dark/h-nodes.svg"
    [[nodiscard]] QString IconPath(const QString &name) const;

    static QString Normalize(const QString &theme);
    static QPalette LightPalette(QPalette base);
    static QPalette DarkPalette(QPalette base);
};

extern ThemeManager *themeManager;
