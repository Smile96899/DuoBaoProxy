#include "system.h"

#include <QApplication>
#include <QClipboard>
#include <QCoreApplication>
#include <QDesktopServices>
#include <QDir>
#include <QIcon>
#include <QProcess>
#include <QSettings>
#include <QUrl>

#ifdef Q_OS_WIN
#include <windows.h>
#include <wininet.h>
#endif

System::System(QObject *parent) : QObject(parent) {}

QString System::platformName() const {
#if defined(Q_OS_WIN)
    return QStringLiteral("h.proxy for Windows");
#elif defined(Q_OS_MACOS)
    return QStringLiteral("h.proxy for macOS");
#else
    return QStringLiteral("h.proxy for Linux");
#endif
}

QString System::appDir() const { return QCoreApplication::applicationDirPath(); }

bool System::isAdmin() const {
#ifdef Q_OS_WIN
    BOOL admin = FALSE;
    PSID group = nullptr;
    SID_IDENTIFIER_AUTHORITY nt = SECURITY_NT_AUTHORITY;
    if (AllocateAndInitializeSid(&nt, 2, SECURITY_BUILTIN_DOMAIN_RID, DOMAIN_ALIAS_RID_ADMINS, 0, 0, 0, 0, 0, 0, &group)) {
        CheckTokenMembership(nullptr, group, &admin);
        FreeSid(group);
    }
    return admin;
#else
    return geteuid() == 0;
#endif
}

// ---- system proxy ---------------------------------------------------------

void System::setSystemProxy(bool on, const QString &address, int port) {
    const QString hostPort = QStringLiteral("%1:%2").arg(address).arg(port);
#if defined(Q_OS_WIN)
    QSettings reg(R"(HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Internet Settings)", QSettings::NativeFormat);
    reg.setValue("ProxyEnable", on ? 1 : 0);
    if (on) {
        reg.setValue("ProxyServer", hostPort);
        reg.setValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>");
    }
    reg.sync();
    InternetSetOptionW(nullptr, INTERNET_OPTION_SETTINGS_CHANGED, nullptr, 0);
    InternetSetOptionW(nullptr, INTERNET_OPTION_REFRESH, nullptr, 0);
#elif defined(Q_OS_MACOS)
    QProcess p;
    p.start("networksetup", {"-listallnetworkservices"});
    p.waitForFinished();
    const auto services = QString::fromUtf8(p.readAllStandardOutput()).split('\n', Qt::SkipEmptyParts);
    for (const auto &s : services) {
        if (s.startsWith('*') || s.startsWith("An asterisk")) continue;
        if (on) {
            QProcess::execute("networksetup", {"-setwebproxy", s, address, QString::number(port)});
            QProcess::execute("networksetup", {"-setsecurewebproxy", s, address, QString::number(port)});
            QProcess::execute("networksetup", {"-setsocksfirewallproxy", s, address, QString::number(port)});
        } else {
            QProcess::execute("networksetup", {"-setwebproxystate", s, "off"});
            QProcess::execute("networksetup", {"-setsecurewebproxystate", s, "off"});
            QProcess::execute("networksetup", {"-setsocksfirewallproxystate", s, "off"});
        }
    }
#else
    if (on) {
        QProcess::execute("gsettings", {"set", "org.gnome.system.proxy", "mode", "manual"});
        for (const char *proto : {"http", "https", "socks"}) {
            QProcess::execute("gsettings", {"set", QStringLiteral("org.gnome.system.proxy.%1").arg(proto), "host", address});
            QProcess::execute("gsettings", {"set", QStringLiteral("org.gnome.system.proxy.%1").arg(proto), "port", QString::number(port)});
        }
    } else {
        QProcess::execute("gsettings", {"set", "org.gnome.system.proxy", "mode", "none"});
    }
#endif
    m_systemProxy = on;
    emit systemProxyChanged();
}

bool System::systemProxyPointsTo(const QString &address, int port) const {
    const QString hostPort = QStringLiteral("%1:%2").arg(address).arg(port);
#if defined(Q_OS_WIN)
    QSettings reg(R"(HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Internet Settings)", QSettings::NativeFormat);
    return reg.value("ProxyEnable").toInt() == 1 && reg.value("ProxyServer").toString() == hostPort;
#elif defined(Q_OS_MACOS)
    QProcess p;
    p.start("scutil", {"--proxy"});
    p.waitForFinished();
    const QString out = QString::fromUtf8(p.readAllStandardOutput());
    return out.contains("HTTPEnable : 1") && out.contains("HTTPProxy : " + address) && out.contains("HTTPPort : " + QString::number(port));
#else
    QProcess p;
    p.start("gsettings", {"get", "org.gnome.system.proxy", "mode"});
    p.waitForFinished();
    return QString::fromUtf8(p.readAllStandardOutput()).contains("manual");
#endif
}

// ---- autostart ------------------------------------------------------------

#if defined(Q_OS_WIN)
static const char *kRunKey = R"(HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run)";
#endif

bool System::autoStart() const {
#if defined(Q_OS_WIN)
    return QSettings(kRunKey, QSettings::NativeFormat).contains("h.proxy");
#elif defined(Q_OS_MACOS)
    return QFile::exists(QDir::homePath() + "/Library/LaunchAgents/proxy.h.plist");
#else
    return QFile::exists(QDir::homePath() + "/.config/autostart/h.proxy.desktop");
#endif
}

void System::setAutoStart(bool on) {
    const QString exe = QDir::toNativeSeparators(QCoreApplication::applicationFilePath());
#if defined(Q_OS_WIN)
    QSettings reg(kRunKey, QSettings::NativeFormat);
    if (on) reg.setValue("h.proxy", QStringLiteral("\"%1\" -tray").arg(exe));
    else reg.remove("h.proxy");
#elif defined(Q_OS_MACOS)
    const QString path = QDir::homePath() + "/Library/LaunchAgents/proxy.h.plist";
    if (on) {
        QFile f(path);
        if (f.open(QIODevice::WriteOnly)) {
            f.write(QStringLiteral("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\"><dict>"
                                   "<key>Label</key><string>proxy.h</string>"
                                   "<key>ProgramArguments</key><array><string>%1</string><string>-tray</string></array>"
                                   "<key>RunAtLoad</key><true/></dict></plist>\n").arg(exe).toUtf8());
        }
    } else {
        QFile::remove(path);
    }
#else
    const QString dir = QDir::homePath() + "/.config/autostart";
    QDir().mkpath(dir);
    const QString path = dir + "/h.proxy.desktop";
    if (on) {
        QFile f(path);
        if (f.open(QIODevice::WriteOnly))
            f.write(QStringLiteral("[Desktop Entry]\nType=Application\nName=h.proxy\nExec=\"%1\" -tray\nX-GNOME-Autostart-enabled=true\n").arg(exe).toUtf8());
    } else {
        QFile::remove(path);
    }
#endif
    emit autoStartChanged();
}

// ---- misc -----------------------------------------------------------------

void System::copyText(const QString &text) { QApplication::clipboard()->setText(text); }
QString System::clipboardText() const { return QApplication::clipboard()->text(); }
void System::openUrl(const QString &url) { QDesktopServices::openUrl(QUrl(url)); }

// ---- tray -----------------------------------------------------------------

void System::setupTray(QObject *) {
    if (!QSystemTrayIcon::isSystemTrayAvailable()) return;
    m_menu = new QMenu();
    m_toggleAction = m_menu->addAction(QStringLiteral("连接"), this, [this] { emit trayToggleRequested(); });
    m_menu->addAction(QStringLiteral("显示窗口"), this, [this] { emit trayShowRequested(); });
    m_menu->addSeparator();
    m_menu->addAction(QStringLiteral("退出"), this, [this] { emit trayQuitRequested(); });
    m_tray = new QSystemTrayIcon(QIcon(":/qt/qml/HProxy/res/mark.svg"), this);
    m_tray->setContextMenu(m_menu);
    m_tray->setToolTip(platformName());
    connect(m_tray, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason r) {
        if (r == QSystemTrayIcon::Trigger || r == QSystemTrayIcon::DoubleClick) emit trayShowRequested();
    });
    m_tray->show();
}

void System::setTrayState(bool connected, const QString &tooltip) {
    if (!m_tray) return;
    m_tray->setToolTip(tooltip);
    if (m_toggleAction) m_toggleAction->setText(connected ? QStringLiteral("断开") : QStringLiteral("连接"));
}
