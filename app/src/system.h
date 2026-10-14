#pragma once
// System integration: system proxy switch, autostart, tray icon, clipboard.
// Windows is implemented fully; macOS and Linux use the common desktop
// commands and degrade to no-ops where a platform has nothing.

#include <QObject>
#include <QSystemTrayIcon>
#include <QMenu>

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

    // Tray
    void setupTray(QObject *window);
    Q_INVOKABLE void setTrayState(bool connected, const QString &tooltip);

signals:
    void systemProxyChanged();
    void autoStartChanged();
    void trayToggleRequested();
    void trayShowRequested();
    void trayQuitRequested();

private:
    bool m_systemProxy = false;
    QSystemTrayIcon *m_tray = nullptr;
    QMenu *m_menu = nullptr;
    QAction *m_toggleAction = nullptr;
};
