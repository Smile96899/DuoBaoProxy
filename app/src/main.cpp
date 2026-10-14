#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QIcon>

#include "core_client.h"
#include "store.h"
#include "system.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QStandardPaths>

// GUI-subsystem apps have no console; keep QML warnings in gui.log.
static void logToFile(QtMsgType type, const QMessageLogContext &, const QString &msg) {
    static QFile f;
    if (!f.isOpen()) {
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
        QDir().mkpath(dir);
        f.setFileName(dir + "/gui.log");
        f.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text);
        if (f.size() > 512 * 1024) { f.resize(0); }
    }
    static const char *levels[] = {"debug", "warn", "critical", "fatal", "info"};
    f.write((QDateTime::currentDateTime().toString("MM-dd HH:mm:ss ") + levels[type] + ": " + msg + QChar('\n')).toUtf8());
    f.flush();
}

int main(int argc, char *argv[]) {
    qInstallMessageHandler(logToFile);
    QApplication app(argc, argv); // QApplication (not QGuiApplication) for the tray menu
    app.setApplicationName("h.proxy");
    app.setOrganizationName("h.proxy");
    app.setApplicationVersion(HP_VERSION);
    app.setQuitOnLastWindowClosed(false);
    app.setWindowIcon(QIcon(":/qt/qml/HProxy/res/mark.svg"));
    QQuickStyle::setStyle("Basic");

    Store store;
    CoreClient core;
    System sys;

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("store", &store);
    engine.rootContext()->setContextProperty("core", &core);
    engine.rootContext()->setContextProperty("sys", &sys);
    engine.rootContext()->setContextProperty("startInTray", app.arguments().contains("-tray"));
    engine.rootContext()->setContextProperty("autoConnect", app.arguments().contains("-connect"));
    // "-import-file <path>": import share links / subscription text at startup.
    QString importText;
    if (const int i = app.arguments().indexOf("-import-file"); i >= 0 && i + 1 < app.arguments().size()) {
        QFile f(app.arguments().at(i + 1));
        if (f.open(QIODevice::ReadOnly)) importText = QString::fromUtf8(f.readAll());
    }
    engine.rootContext()->setContextProperty("importText", importText);
    engine.loadFromModule("HProxy", "Main");
    if (engine.rootObjects().isEmpty()) return 1;

    sys.setupTray(engine.rootObjects().first());
    core.launch();
    const int rc = app.exec();
    core.shutdown();
    return rc;
}
