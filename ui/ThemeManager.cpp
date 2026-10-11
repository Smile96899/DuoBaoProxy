#include <QStyle>
#include <QApplication>
#include <QStyleFactory>
#include <QPalette>
#include <QColor>

#include "ThemeManager.hpp"

ThemeManager *themeManager = new ThemeManager;

extern QString ReadFileText(const QString &path);

QString ThemeManager::Normalize(const QString &theme) {
    // 旧配置可能保存了 feiyangqingyun 主题编号或 QStyle 名称，一律归并为浅色/深色
    return theme == "1" ? "1" : "0";
}

QString ThemeManager::IconPath(const QString &name) const {
    return QStringLiteral(":/icon/%1/%2.svg").arg(IsDark() ? "dark" : "light", name);
}

void ThemeManager::ApplyTheme(const QString &theme) {
    auto id = Normalize(theme);
    if (id == current_theme) return;
    current_theme = id;

    // 统一使用 Fusion 风格，保证样式表在各 Windows 版本上渲染一致
    auto style = QStyleFactory::create("Fusion");
    if (style == nullptr) style = QStyleFactory::create(qApp->style()->objectName());
    if (style != nullptr) qApp->setStyle(style);
    auto base = style != nullptr ? style->standardPalette() : qApp->palette();
    qApp->setPalette(IsDark() ? DarkPalette(base) : LightPalette(base));
    qApp->setStyleSheet(ReadFileText(IsDark() ? ":/neko/h-dark.css" : ":/neko/h-light.css"));

    if (on_theme_changed) on_theme_changed();
}

QPalette ThemeManager::LightPalette(QPalette pal) {
    const QColor window("#F4F4F1"), base("#FFFFFF"), text("#1F2428"), accent("#B98A2E"), disabled("#A0A5A3");
    pal.setColor(QPalette::Window, window);
    pal.setColor(QPalette::Base, base);
    pal.setColor(QPalette::AlternateBase, base);
    pal.setColor(QPalette::Button, base);
    pal.setColor(QPalette::WindowText, text);
    pal.setColor(QPalette::Text, text);
    pal.setColor(QPalette::ButtonText, text);
    pal.setColor(QPalette::ToolTipBase, base);
    pal.setColor(QPalette::ToolTipText, text);
    pal.setColor(QPalette::PlaceholderText, QColor("#9AA09E"));
    pal.setColor(QPalette::Highlight, accent);
    pal.setColor(QPalette::HighlightedText, QColor("#FFFFFF"));
    pal.setColor(QPalette::Link, accent);
    pal.setColor(QPalette::LinkVisited, accent);
    pal.setColor(QPalette::Light, QColor("#FFFFFF"));
    pal.setColor(QPalette::Midlight, QColor("#ECECE8"));
    pal.setColor(QPalette::Mid, QColor("#D5D5D0"));
    pal.setColor(QPalette::Dark, QColor("#B9BFBD"));
    pal.setColor(QPalette::Shadow, QColor("#9AA09E"));
    pal.setColor(QPalette::Disabled, QPalette::Text, disabled);
    pal.setColor(QPalette::Disabled, QPalette::WindowText, disabled);
    pal.setColor(QPalette::Disabled, QPalette::ButtonText, disabled);
    return pal;
}

QPalette ThemeManager::DarkPalette(QPalette pal) {
    const QColor window("#12151B"), base("#171B23"), text("#E4E7EC"), accent("#E3B95F"), disabled("#5F6775");
    pal.setColor(QPalette::Window, window);
    pal.setColor(QPalette::Base, base);
    pal.setColor(QPalette::AlternateBase, base);
    pal.setColor(QPalette::Button, QColor("#1F242E"));
    pal.setColor(QPalette::WindowText, text);
    pal.setColor(QPalette::Text, text);
    pal.setColor(QPalette::ButtonText, text);
    pal.setColor(QPalette::ToolTipBase, QColor("#1F242E"));
    pal.setColor(QPalette::ToolTipText, text);
    pal.setColor(QPalette::PlaceholderText, QColor("#5F6775"));
    pal.setColor(QPalette::Highlight, accent);
    pal.setColor(QPalette::HighlightedText, QColor("#15120A"));
    pal.setColor(QPalette::Link, accent);
    pal.setColor(QPalette::LinkVisited, accent);
    pal.setColor(QPalette::Light, QColor("#2E3441"));
    pal.setColor(QPalette::Midlight, QColor("#262B36"));
    pal.setColor(QPalette::Mid, QColor("#1F242E"));
    pal.setColor(QPalette::Dark, QColor("#0C0E13"));
    pal.setColor(QPalette::Shadow, QColor("#000000"));
    pal.setColor(QPalette::Disabled, QPalette::Text, disabled);
    pal.setColor(QPalette::Disabled, QPalette::WindowText, disabled);
    pal.setColor(QPalette::Disabled, QPalette::ButtonText, disabled);
    return pal;
}
