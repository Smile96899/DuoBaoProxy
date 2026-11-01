#pragma once
// CoreClient owns the DuoBaoProxyCore child process and the gRPC channel to it.
// Every call is asynchronous; results come back as signals so QML never
// blocks. Request ids let QML match replies to the row that asked.

#include <QObject>
#include <QProcess>
#include <QStringList>
#include <QTimer>
#include <QVariant>
#include <memory>

namespace libcore::LibcoreService { class Client; }
class QAbstractGrpcChannel;

class CoreClient : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged)
    Q_PROPERTY(QString runningCore READ runningCore NOTIFY runningCoreChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)
public:
    explicit CoreClient(QObject *parent = nullptr);
    ~CoreClient() override;

    bool ready() const { return m_ready; }
    QString runningCore() const { return m_runningCore; }
    QString lastError() const { return m_lastError; }

    // Launches DuoBaoProxyCore next to the executable and connects to it.
    Q_INVOKABLE void launch();
    Q_INVOKABLE void shutdown();

    Q_INVOKABLE void listCores();
    Q_INVOKABLE void startCore(const QString &coreId, const QString &config, const QStringList &statsTags, const QString &assetDir);
    Q_INVOKABLE void stopCore();
    Q_INVOKABLE void status();
    // UrlTest through a throw-away instance of coreId built from config.
    Q_INVOKABLE void urlTest(int requestId, const QString &coreId, const QString &config, const QString &url, int timeoutMs);
    Q_INVOKABLE void tcpPing(int requestId, const QString &address, int timeoutMs);
    // One named check (icmp, loss, udp, download, upload, geo, media, ai, dnsleak);
    // answers via probed with a JSON report.
    Q_INVOKABLE void probe(int requestId, const QString &name, const QString &coreId, const QString &config,
                           const QString &address, const QString &url, int count, int timeoutMs);
    Q_INVOKABLE void importNodes(const QString &text);
    Q_INVOKABLE void shareLink(const QString &nodeJson);
    Q_INVOKABLE void renderConfig(int requestId, const QString &coreId, const QStringList &chainJson, const QString &settingsJson, int mode);
    // Traffic polling for the running core; emits traffic() every second.
    Q_INVOKABLE void watchTraffic(const QStringList &tags);
    Q_INVOKABLE void unwatchTraffic();

signals:
    void readyChanged();
    void runningCoreChanged();
    void lastErrorChanged();
    void coresListed(const QVariantList &cores);
    void started(const QString &error);
    void stopped(const QString &error);
    void tested(int requestId, int ms, const QString &error);
    void probed(int requestId, int ms, const QString &report, const QString &error);
    void imported(const QStringList &nodesJson, const QStringList &errors);
    void linkReady(const QString &link, const QString &error);
    void rendered(int requestId, const QString &config, const QStringList &statsTags, const QString &error);
    void traffic(qint64 upBytesPerSec, qint64 downBytesPerSec, qint64 upTotal, qint64 downTotal);
    void logLine(const QString &line);

private:
    void setError(const QString &e);
    void setRunning(const QString &core);
    void pollTraffic();

    QProcess m_proc;
    QString m_token;
    int m_port = 19810;
    bool m_ready = false;
    QString m_runningCore;
    QString m_lastError;
    std::shared_ptr<QAbstractGrpcChannel> m_channel;
    std::unique_ptr<libcore::LibcoreService::Client> m_client;
    QTimer m_trafficTimer;
    QStringList m_trafficTags;
    qint64 m_lastUp = 0, m_lastDown = 0;
};
