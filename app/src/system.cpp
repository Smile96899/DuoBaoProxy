#include "system.h"

#include <QApplication>
#include <QClipboard>
#include <QCoreApplication>
#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QDirIterator>
#include <QFileInfo>
#include <QMap>
#include <QSet>
#include <QIcon>
#include <QActionGroup>
#include <QFile>
#include <QPainter>
#include <QScreen>
#include <QStandardPaths>
#include <QProcess>
#include <QSettings>
#include <QUrl>

#ifdef Q_OS_WIN
#include <windows.h>
#include <wininet.h>
#include <dwmapi.h>
#include <shlobj.h>
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

void System::setDarkTitleBar(QWindow *window, bool dark) {
#ifdef Q_OS_WIN
    if (!window) return;
    const BOOL on = dark;
    // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on Windows 10 20H1+ and 11, 19 on older 10 builds.
    const HWND hwnd = reinterpret_cast<HWND>(window->winId());
    if (FAILED(DwmSetWindowAttribute(hwnd, 20, &on, sizeof(on))))
        DwmSetWindowAttribute(hwnd, 19, &on, sizeof(on));
    // Repaint the frame now rather than on the next activation.
    SetWindowPos(hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
#else
    Q_UNUSED(window) Q_UNUSED(dark)
#endif
}

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

#ifdef Q_OS_WIN
// Reads a .lnk with an already created IShellLink; "" when it has no file target.
static QString shortcutTarget(IShellLinkW *link, const QString &path) {
    QString target;
    IPersistFile *file = nullptr;
    if (SUCCEEDED(link->QueryInterface(IID_IPersistFile, reinterpret_cast<void **>(&file)))) {
        const std::wstring native = QDir::toNativeSeparators(path).toStdWString();
        wchar_t buf[MAX_PATH] = {};
        if (SUCCEEDED(file->Load(native.c_str(), STGM_READ))
            && SUCCEEDED(link->GetPath(buf, MAX_PATH, nullptr, 0)) && buf[0])
            target = QString::fromWCharArray(buf);
        file->Release();
    }
    return target;
}

// Runs fn with a ShellLink object, initialising COM for the call if needed.
template <typename Fn>
static void withShellLink(Fn fn) {
    const bool comInit = SUCCEEDED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED));
    IShellLinkW *link = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_IShellLinkW, reinterpret_cast<void **>(&link)))) {
        fn(link);
        link->Release();
    }
    if (comInit)
        CoUninitialize();
}
#endif

QString System::resolveShortcut(const QString &path) const {
    if (!path.endsWith(QLatin1String(".lnk"), Qt::CaseInsensitive))
        return path;
#ifdef Q_OS_WIN
    QString target;
    withShellLink([&](IShellLinkW *link) { target = shortcutTarget(link, path); });
    return target;
#else
    return path;
#endif
}

QVariantList System::installedApps() const {
    QVariantList out;
#ifdef Q_OS_WIN
    const QString startMenu = QStringLiteral("/Microsoft/Windows/Start Menu/Programs");
    const QStringList dirs = {
        QStandardPaths::writableLocation(QStandardPaths::DesktopLocation),
        qEnvironmentVariable("PUBLIC") + QStringLiteral("/Desktop"),
        qEnvironmentVariable("APPDATA") + startMenu,
        qEnvironmentVariable("ProgramData") + startMenu,
    };
    QMap<QString, QVariantMap> byName;      // sorted, case-insensitive key
    QSet<QString> seen;                     // lower-cased exe paths
    withShellLink([&](IShellLinkW *link) {
        for (const QString &dir : dirs) {
            QDirIterator it(dir, {QStringLiteral("*.lnk")}, QDir::Files, QDirIterator::Subdirectories);
            while (it.hasNext()) {
                const QString lnk = it.next();
                const QString name = QFileInfo(lnk).completeBaseName();
                if (name.contains(QLatin1String("uninstall"), Qt::CaseInsensitive) || name.contains(QStringLiteral("卸载")))
                    continue;
                const QString target = shortcutTarget(link, lnk);
                if (!target.endsWith(QLatin1String(".exe"), Qt::CaseInsensitive) || !QFileInfo::exists(target))
                    continue;
                if (seen.contains(target.toLower()))
                    continue;
                seen.insert(target.toLower());
                byName.insert(name.toLower() + QLatin1Char('|') + target.toLower(), QVariantMap{{"name", name}, {"path", target}});
            }
        }
    });
    for (const QVariantMap &m : std::as_const(byName))
        out.append(m);
#endif
    return out;
}

// ---- tray -----------------------------------------------------------------

// The logo with its circle in the state colour: off grey, on green, tun red.
static QIcon logoIcon(const QString &look) {
    return QIcon(QStringLiteral(":/qt/qml/HProxy/res/logo-%1.png").arg(look));
}

void System::setupTray(QObject *) {
    if (!QSystemTrayIcon::isSystemTrayAvailable()) return;
    m_tray = new QSystemTrayIcon(logoIcon("off"), this);
    m_tray->setToolTip(platformName());
    connect(m_tray, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason r) {
        if (r == QSystemTrayIcon::Trigger || r == QSystemTrayIcon::DoubleClick) emit trayShowRequested();
    });
    rebuildTrayMenus();
    m_tray->show();
}

void System::setTrayState(bool connected, const QString &tooltip) {
    if (!m_tray) return;
    m_tray->setToolTip(tooltip);
    if (m_connected == connected) return;
    m_connected = connected;
    rebuildTrayMenus();
}

void System::setTrayLook(const QString &look) {
    if (!m_tray) return;
    m_tray->setIcon(logoIcon(look == "tun" || look == "on" ? look : QStringLiteral("off")));
}

void System::notify(const QString &title, const QString &text) {
    if (m_tray) m_tray->showMessage(title, text, QIcon(QStringLiteral(":/qt/qml/HProxy/res/logo.png")), 3000);
}

void System::setTrayModel(const QVariantList &nodes, int selected, const QString &mode, bool hasTun) {
    m_nodes = nodes;
    m_selected = selected;
    m_mode = mode;
    m_hasTun = hasTun;
    rebuildTrayMenus();
}

// Builds a fresh menu and swaps it in. Editing a menu the tray already holds
// (clear + add submenus) overflows the stack inside qwindows on Qt 6.8.
void System::rebuildTrayMenus() {
    if (!m_tray) return;
    auto *menu = new QMenu();
    menu->addAction(m_connected ? QStringLiteral("停止") : QStringLiteral("启动"), this, [this] { emit trayToggleRequested(); });

    QMenu *modeMenu = menu->addMenu(QStringLiteral("启动方式"));
    auto *modes = new QActionGroup(modeMenu);
    QList<QPair<QString, QString>> modeList{{"system", QStringLiteral("系统代理")}};
    if (m_hasTun) modeList.append({"tun", QStringLiteral("TUN")});
    for (const auto &[v, label] : modeList) {
        QAction *a = modeMenu->addAction(label, this, [this, v] { emit trayModePicked(v); });
        a->setCheckable(true);
        a->setChecked(m_mode == v);
        modes->addAction(a);
    }

    // One submenu per group (flat when there is only one), nodes top to bottom.
    QMenu *nodeMenu = menu->addMenu(QStringLiteral("选择节点"));
    auto *picks = new QActionGroup(nodeMenu);
    QMap<QString, QMenu *> groups;
    QStringList order;
    for (const QVariant &v : m_nodes) {
        const QString g = v.toMap().value("group").toString();
        if (!groups.contains(g)) { groups[g] = nullptr; order << g; }
    }
    for (const QString &g : order) groups[g] = order.size() == 1 ? nodeMenu : nodeMenu->addMenu(g);
    for (const QVariant &v : m_nodes) {
        const QVariantMap n = v.toMap();
        const int id = n.value("id").toInt();
        const int lat = n.value("lat").toInt();
        QString label = n.value("name").toString();
        if (lat > 0) label += QStringLiteral("\t%1 ms").arg(lat);
        else if (lat < 0) label += QStringLiteral("\t超时");
        QAction *a = groups[n.value("group").toString()]->addAction(label, this, [this, id] { emit trayNodePicked(id); });
        a->setCheckable(true);
        a->setChecked(id == m_selected);
        a->setEnabled(n.value("ok").toBool());
        picks->addAction(a);
    }
    if (m_nodes.isEmpty()) nodeMenu->addAction(QStringLiteral("还没有节点"))->setEnabled(false);

    QMenu *importMenu = menu->addMenu(QStringLiteral("导入"));
    importMenu->addAction(QStringLiteral("从剪贴板导入"), this, [this] { emit trayImportClipboardRequested(); });
    importMenu->addAction(QStringLiteral("扫描屏幕上的二维码"), this, [this] { emit trayScanRequested(); });
    menu->addSeparator();
    menu->addAction(QStringLiteral("显示窗口"), this, [this] { emit trayShowRequested(); });
    menu->addSeparator();
    menu->addAction(QStringLiteral("退出"), this, [this] { emit trayQuitRequested(); });

    m_tray->setContextMenu(menu);
    if (m_menu) m_menu->deleteLater();
    m_menu = menu;
}

// ---- QR -------------------------------------------------------------------

void System::scanScreenQr() {
    // Every screen side by side in one image.
    QList<QPixmap> shots;
    int w = 0, h = 0;
    for (QScreen *s : QGuiApplication::screens()) {
        QPixmap p = s->grabWindow(0);
        w += p.width();
        h = qMax(h, p.height());
        shots << p;
    }
    if (shots.isEmpty() || w == 0) { emit qrScanned({}, QStringLiteral("截屏失败")); return; }
    QImage all(w, h, QImage::Format_RGB32);
    all.fill(Qt::white);
    QPainter painter(&all);
    int x = 0;
    for (const QPixmap &p : shots) { painter.drawPixmap(x, 0, p); x += p.width(); }
    painter.end();

    const QString file = QDir(QStandardPaths::writableLocation(QStandardPaths::TempLocation)).filePath("hproxy-qr.png");
    if (!all.save(file)) { emit qrScanned({}, QStringLiteral("截屏失败")); return; }

    auto *proc = new QProcess(this);
    connect(proc, &QProcess::finished, this, [this, proc, file](int code, QProcess::ExitStatus) {
        const QString out = QString::fromUtf8(proc->readAllStandardOutput()).trimmed();
        QFile::remove(file);
        proc->deleteLater();
        if (code == 0 && !out.isEmpty()) emit qrScanned(out, {});
        else emit qrScanned({}, code == 2 ? QStringLiteral("屏幕上没有找到二维码") : QStringLiteral("二维码识别失败"));
    });
#ifdef Q_OS_WIN
    proc->start(appDir() + "/h_core.exe", {"qr", file});
#else
    proc->start(appDir() + "/h_core", {"qr", file});
#endif
}

void System::makeQrImages(const QStringList &texts) {
    if (!m_qrDir.isEmpty()) QDir(m_qrDir).removeRecursively();
    m_qrDir = QDir(QStandardPaths::writableLocation(QStandardPaths::TempLocation))
                  .filePath(QStringLiteral("hproxy-share-%1").arg(QDateTime::currentMSecsSinceEpoch()));
    QDir().mkpath(m_qrDir);

    auto *proc = new QProcess(this);
    connect(proc, &QProcess::finished, this, [this, proc](int code, QProcess::ExitStatus) {
        const QStringList paths = QString::fromUtf8(proc->readAllStandardOutput()).split('\n', Qt::SkipEmptyParts);
        const QString err = QString::fromUtf8(proc->readAllStandardError()).trimmed();
        proc->deleteLater();
        QStringList urls;
        for (const QString &p : paths) urls << QUrl::fromLocalFile(p.trimmed()).toString();
        emit qrImagesReady(urls, code == 0 ? QString() : QStringLiteral("二维码生成失败：%1").arg(err));
    });
#ifdef Q_OS_WIN
    proc->start(appDir() + "/h_core.exe", {"qr-encode", m_qrDir});
#else
    proc->start(appDir() + "/h_core", {"qr-encode", m_qrDir});
#endif
    proc->write(texts.join('\n').toUtf8() + '\n');
    proc->closeWriteChannel();
}
