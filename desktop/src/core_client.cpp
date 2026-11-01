#include "core_client.h"

#include <QCoreApplication>
#include <QDir>
#include <QGrpcCallOptions>
#include <QGrpcCallReply>
#include <QGrpcChannelOptions>
#include <QGrpcHttp2Channel>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QTcpServer>
#include <QUrl>

#include "libcore.qpb.h"
#include "libcore_client.grpc.qpb.h"

using namespace libcore;

namespace {
QString stripAnsi(QByteArray l) {
    static const QRegularExpression ansi(QStringLiteral("\x1b\\[[0-9;]*m"));
    return QString::fromUtf8(l.trimmed()).remove(ansi);
}
int freePort() {
    QTcpServer s;
    if (s.listen(QHostAddress::LocalHost, 0)) return s.serverPort();
    return 19810;
}
QString randomToken() {
    static const char alphabet[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    QString t;
    for (int i = 0; i < 32; ++i) t += alphabet[QRandomGenerator::system()->bounded(62)];
    return t;
}
} // namespace

CoreClient::CoreClient(QObject *parent) : QObject(parent) {
    m_trafficTimer.setInterval(1000);
    connect(&m_trafficTimer, &QTimer::timeout, this, &CoreClient::pollTraffic);
    connect(&m_proc, &QProcess::readyReadStandardError, this, [this] {
        for (const auto &l : m_proc.readAllStandardError().split('\n'))
            if (!l.trimmed().isEmpty()) emit logLine(QString::fromUtf8(l.trimmed()));
    });
    connect(&m_proc, &QProcess::readyReadStandardOutput, this, [this] {
        for (const auto &l : m_proc.readAllStandardOutput().split('\n'))
            if (!l.trimmed().isEmpty()) emit logLine(QString::fromUtf8(l.trimmed()));
    });
    connect(&m_proc, &QProcess::finished, this, [this](int code, QProcess::ExitStatus) {
        m_ready = false;
        emit readyChanged();
        setRunning({});
        emit logLine(QStringLiteral("核心已退出（%1）").arg(code));
    });
}

CoreClient::~CoreClient() { shutdown(); }

void CoreClient::launch() {
    if (m_proc.state() != QProcess::NotRunning) return;
    m_port = freePort();
    m_token = randomToken();
    const QString exe = QCoreApplication::applicationDirPath() + QDir::separator() +
#ifdef Q_OS_WIN
        "DuoBaoProxyCore.exe";
#else
        "DuoBaoProxyCore";
#endif
    // The core writes core.log into its working directory; the install dir may be read-only.
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dataDir);
    m_proc.setWorkingDirectory(dataDir);
    m_proc.start(exe, {"run", "-port", QString::number(m_port), "-token", m_token});
    if (!m_proc.waitForStarted(5000)) {
        setError(QStringLiteral("无法启动核心：%1").arg(exe));
        return;
    }
    QGrpcChannelOptions opts;
    opts.setMetadata({{"duobao_auth", m_token.toUtf8()}});
    m_channel = std::make_shared<QGrpcHttp2Channel>(QUrl(QStringLiteral("http://127.0.0.1:%1").arg(m_port)), opts);
    m_client = std::make_unique<LibcoreService::Client>();
    m_client->attachChannel(m_channel);
    m_ready = true;
    emit readyChanged();
    status();
}

void CoreClient::shutdown() {
    unwatchTraffic();
    if (m_proc.state() == QProcess::NotRunning) return;
    if (m_client) {
        auto reply = m_client->Exit(EmptyReq());
        Q_UNUSED(reply);
    }
    if (!m_proc.waitForFinished(1500)) {
        m_proc.kill();
        m_proc.waitForFinished(1000);   // launch() right after needs NotRunning
    }
}

void CoreClient::setError(const QString &e) {
    m_lastError = e;
    emit lastErrorChanged();
    if (!e.isEmpty()) emit logLine(QStringLiteral("错误：") + e);
}

void CoreClient::setRunning(const QString &core) {
    if (m_runningCore == core) return;
    m_runningCore = core;
    emit runningCoreChanged();
}

// Helper: run a unary call and hand the typed reply (or an error string) to fn.
template <typename Resp, typename Reply, typename Fn>
static void await(std::unique_ptr<Reply> reply, QObject *ctx, Fn fn) {
    auto *r = reply.release();
    QObject::connect(r, &QGrpcCallReply::finished, ctx, [r, fn](const QGrpcStatus &st) {
        if (st.isOk()) {
            if (auto v = r->template read<Resp>()) fn(*v, QString());
            else fn(Resp(), QStringLiteral("bad reply"));
        } else {
            fn(Resp(), st.message());
        }
        r->deleteLater();
    });
}

#define REQUIRE_CLIENT(errSignal) \
    if (!m_client) { errSignal; return; }

void CoreClient::listCores() {
    REQUIRE_CLIENT(emit coresListed({}))
    await<ListCoresResp>(m_client->ListCores(EmptyReq()), this, [this](const ListCoresResp &r, const QString &err) {
        if (!err.isEmpty()) { setError(err); emit coresListed({}); return; }
        QVariantList out;
        for (const auto &c : r.cores()) {
            QVariantMap m;
            m["id"] = c.id_proto();
            m["version"] = c.version();
            m["protocols"] = c.protocols();
            m["running"] = c.running();
            out << m;
            if (c.running()) setRunning(c.id_proto());
        }
        emit coresListed(out);
    });
}

void CoreClient::startCore(const QString &coreId, const QString &config, const QStringList &statsTags, const QString &assetDir) {
    REQUIRE_CLIENT(emit started("core not ready"))
    LoadConfigReq req;
    req.setCoreId(coreId);
    req.setCoreConfig(config);
    req.setStatsOutbounds(statsTags);
    req.setAssetDir(assetDir);
    await<ErrorResp>(m_client->Start(req), this, [this, coreId](const ErrorResp &r, const QString &err) {
        const QString e = err.isEmpty() ? r.error() : err;
        if (e.isEmpty()) setRunning(coreId); else setError(e);
        emit started(e);
    });
}

void CoreClient::stopCore() {
    REQUIRE_CLIENT(emit stopped("core not ready"))
    await<ErrorResp>(m_client->Stop(StopReq()), this, [this](const ErrorResp &r, const QString &err) {
        const QString e = err.isEmpty() ? r.error() : err;
        setRunning({});
        unwatchTraffic();
        emit stopped(e);
    });
}

void CoreClient::status() {
    REQUIRE_CLIENT((void)0)
    await<StatusResp>(m_client->Status(EmptyReq()), this, [this](const StatusResp &r, const QString &err) {
        if (err.isEmpty()) setRunning(r.runningCoreId());
    });
}

void CoreClient::urlTest(int requestId, const QString &coreId, const QString &config, const QString &url, int timeoutMs) {
    REQUIRE_CLIENT(emit tested(requestId, 0, "core not ready"))
    TestReq req;
    req.setMode(TestModeGadget::TestMode::UrlTest);
    req.setUrl(url);
    req.setTimeout(timeoutMs);
    if (!config.isEmpty()) {
        LoadConfigReq cfg;
        cfg.setCoreId(coreId);
        cfg.setCoreConfig(config);
        req.setConfig(cfg);
    }
    await<TestResp>(m_client->Test(req), this, [this, requestId](const TestResp &r, const QString &err) {
        emit tested(requestId, r.ms(), err.isEmpty() ? r.error() : err);
    });
}

void CoreClient::tcpPing(int requestId, const QString &address, int timeoutMs) {
    REQUIRE_CLIENT(emit tested(requestId, 0, "core not ready"))
    TestReq req;
    req.setMode(TestModeGadget::TestMode::TcpPing);
    req.setAddress(address);
    req.setTimeout(timeoutMs);
    await<TestResp>(m_client->Test(req), this, [this, requestId](const TestResp &r, const QString &err) {
        emit tested(requestId, r.ms(), err.isEmpty() ? r.error() : err);
    });
}

void CoreClient::probe(int requestId, const QString &name, const QString &coreId, const QString &config,
                       const QString &address, const QString &url, int count, int timeoutMs) {
    REQUIRE_CLIENT(emit probed(requestId, 0, {}, "core not ready"))
    TestReq req;
    req.setMode(TestModeGadget::TestMode::Probe);
    req.setProbe(name);
    req.setAddress(address);
    req.setUrl(url);
    req.setCount(count);
    req.setTimeout(timeoutMs);
    if (!config.isEmpty()) {
        LoadConfigReq cfg;
        cfg.setCoreId(coreId);
        cfg.setCoreConfig(config);
        req.setConfig(cfg);
    }
    await<TestResp>(m_client->Test(req), this, [this, requestId](const TestResp &r, const QString &err) {
        emit probed(requestId, r.ms(), r.fullReport(), err.isEmpty() ? r.error() : err);
    });
}

void CoreClient::importNodes(const QString &text) {
    REQUIRE_CLIENT(emit imported({}, {"core not ready"}))
    ImportNodesReq req;
    req.setText(text);
    await<ImportNodesResp>(m_client->ImportNodes(req), this, [this](const ImportNodesResp &r, const QString &err) {
        if (!err.isEmpty()) { emit imported({}, {err}); return; }
        emit imported(r.nodesJson(), r.errors());
    });
}

void CoreClient::shareLink(const QString &nodeJson) {
    REQUIRE_CLIENT(emit linkReady({}, "core not ready"))
    ShareLinkReq req;
    req.setNodeJson(nodeJson);
    await<ShareLinkResp>(m_client->ShareLink(req), this, [this](const ShareLinkResp &r, const QString &err) {
        emit linkReady(r.link(), err.isEmpty() ? r.error() : err);
    });
}

void CoreClient::renderConfig(int requestId, const QString &coreId, const QStringList &chainJson, const QString &settingsJson, int mode) {
    REQUIRE_CLIENT(emit rendered(requestId, {}, {}, "core not ready"))
    RenderConfigReq req;
    req.setCoreId(coreId);
    req.setChainJson(chainJson);
    req.setSettingsJson(settingsJson);
    req.setMode(static_cast<RenderModeGadget::RenderMode>(mode));
    await<RenderConfigResp>(m_client->RenderConfig(req), this, [this, requestId](const RenderConfigResp &r, const QString &err) {
        emit rendered(requestId, r.config(), r.statsTags(), err.isEmpty() ? r.error() : err);
    });
}

void CoreClient::watchTraffic(const QStringList &tags) {
    m_trafficTags = tags;
    m_lastUp = m_lastDown = 0;
    m_trafficTimer.start();
}

void CoreClient::unwatchTraffic() { m_trafficTimer.stop(); }

void CoreClient::pollTraffic() {
    if (!m_client || m_trafficTags.isEmpty()) return;
    // Only the exit tag ("proxy") carries user traffic; hops double count.
    QueryStatsReq up, down;
    up.setTag(m_trafficTags.first());
    up.setDirect("uplink");
    down.setTag(m_trafficTags.first());
    down.setDirect("downlink");
    auto upReply = m_client->QueryStats(up);
    auto *u = upReply.release();
    connect(u, &QGrpcCallReply::finished, this, [this, u, down](const QGrpcStatus &st) {
        qint64 upTotal = 0;
        if (st.isOk()) if (auto v = u->read<QueryStatsResp>()) upTotal = v->traffic();
        u->deleteLater();
        auto downReply = m_client->QueryStats(down);
        auto *d = downReply.release();
        connect(d, &QGrpcCallReply::finished, this, [this, d, upTotal](const QGrpcStatus &st2) {
            qint64 downTotal = 0;
            if (st2.isOk()) if (auto v = d->read<QueryStatsResp>()) downTotal = v->traffic();
            d->deleteLater();
            const qint64 upRate = m_lastUp ? upTotal - m_lastUp : 0;
            const qint64 downRate = m_lastDown ? downTotal - m_lastDown : 0;
            m_lastUp = upTotal;
            m_lastDown = downTotal;
            emit traffic(qMax<qint64>(0, upRate), qMax<qint64>(0, downRate), upTotal, downTotal);
        });
    });
}
