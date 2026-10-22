#pragma once
// System integration: system proxy switch, autostart, tray icon, clipboard.
// Windows is implemented fully; macOS and Linux use the common desktop
// commands and degrade to no-ops where a platform has nothing.

#include <QObject>
#include <QSystemTrayIcon>
#include <QMenu>
#include <QVariantList>
#include <QWindow>

class System : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool systemProxy READ systemProxy NOTIFY systemProxyChanged)
    Q_PROPERTY(bool autoStart READ autoStart WRITE setAutoStart NOTIFY autoStartChanged)
    Q_PROPERTY(bool isAdmin READ isAdmin CONSTANT)
    Q_PROPERTY(QString platformName READ platformName CONSTANT)
public:
    explicit System(QObject *parent = nullptr);

    bool systemProxy() const { return m_systemProxy; }
    Q_INVOKABLE void setSystemProxy(bool on, const QString &address, int port);
    // True when the OS proxy is enabled and points at address:port (ours).
    Q_INVOKABLE bool systemProxyPointsTo(const QString &address, int port) const;
    bool autoStart() const;
    void setAutoStart(bool on);
    bool isAdmin() const;
    QString platformName() const;

    Q_INVOKABLE void copyText(const QString &text);
    Q_INVOKABLE QString clipboardText() const;
    Q_INVOKABLE void openUrl(const QString &url);
    Q_INVOKABLE QString appDir() const;
    // Target of a Windows shortcut (.lnk); other paths come back unchanged,
    // and a shortcut with no file target (Store apps) gives "".
    Q_INVOKABLE QString resolveShortcut(const QString &path) const;
    // Programs with a shortcut on either desktop (user and Public) or in
    // either Start Menu: [{name, path}], one per exe, sorted by name.
    Q_INVOKABLE QVariantList installedApps() const;
    // Windows draws the title bar itself; tell it to follow the app theme.
    Q_INVOKABLE void setDarkTitleBar(QWindow *window, bool dark);

    // Tray
    void setupTray(QObject *window);
    Q_INVOKABLE void setTrayState(bool connected, const QString &tooltip);
    // Tints the rabbit: "off" grey, "on" green, "tun" red.
    Q_INVOKABLE void setTrayLook(const QString &look);
    Q_INVOKABLE void notify(const QString &title, const QString &text);
    // What the right-click menu offers. nodes: [{id, name, group, lat, ok}]
    Q_INVOKABLE void setTrayModel(const QVariantList &nodes, int selected, const QString &mode, bool hasTun);

    // Grabs every screen and decodes QR codes with h_core; answers via qrScanned.
    Q_INVOKABLE void scanScreenQr();
    // QR images for the given texts, made by h_core; answers via qrImagesReady (file URLs, in order).
    Q_INVOKABLE void makeQrImages(const QStringList &texts);

signals:
    void systemProxyChanged();
    void autoStartChanged();
    void trayToggleRequested();
    void trayShowRequested();
    void trayQuitRequested();
    void trayNodePicked(int id);
    void trayModePicked(const QString &mode);
    void trayImportClipboardRequested();
    void trayScanRequested();
    void qrScanned(const QString &text, const QString &error);
    void qrImagesReady(const QStringList &urls, const QString &error);

private:
    bool m_systemProxy = false;
    QSystemTrayIcon *m_tray = nullptr;
    QMenu *m_menu = nullptr;
    bool m_connected = false;
    QString m_qrDir;            // last batch of share QR images, removed on the next
    QVariantList m_nodes;
    int m_selected = -1;
    QString m_mode;
    bool m_hasTun = false;
    void rebuildTrayMenus();
};
