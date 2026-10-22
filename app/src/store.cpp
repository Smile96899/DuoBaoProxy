#include "store.h"

#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJSValue>
#include <QSaveFile>
#include <QStandardPaths>

Store::Store(QObject *parent) : QObject(parent) {
    m_dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(m_dir);
    m_file = m_dir + "/data.json";
    load();
}

void Store::load() {
    QFile f(m_file);
    QJsonObject root;
    if (f.open(QIODevice::ReadOnly)) root = QJsonDocument::fromJson(f.readAll()).object();
    m_nodes = root.value("nodes").toArray().toVariantList();
    m_groups = root.value("groups").toArray().toVariantList();
    m_settings = root.value("settings").toObject().toVariantMap();
    m_ui = root.value("ui").toObject().toVariantMap();
    m_nextId = root.value("next_id").toInt(1);

    // Defaults the first time.
    if (m_groups.isEmpty()) m_groups << QVariantMap{{"name", QStringLiteral("默认")}};
    auto def = [this](const QString &k, const QVariant &v) { if (!m_ui.contains(k)) m_ui[k] = v; };
    def("layout", "landscape");
    def("theme", "system");
    def("core", "sing-box");
    def("selected", -1);
    def("proxy_mode", "rule");   // rule | global | direct
    def("mode", "system");       // system | tun
    def("test_url", "http://cp.cloudflare.com/");
    if (m_settings.isEmpty()) {
        QVariantMap inbound{{"address", "127.0.0.1"}, {"port", 2080}, {"sniff", 1}};
        QVariantMap routing{
            {"direct_domain", QVariantList{"geosite:cn", "geosite:private"}},
            {"direct_ip", QVariantList{"geoip:cn", "geoip:private"}},
            {"block_domain", QVariantList{"geosite:category-ads-all"}},
            {"final", "proxy"}, {"dns_routing", true},
            {"remote_dns", "https://dns.google/dns-query"}, {"direct_dns", "https://doh.pub/dns-query"},
        };
        QVariantMap tun{{"enabled", false}, {"stack", "gvisor"}, {"mtu", 9000}};
        QVariantMap mux{{"protocol", "h2mux"}, {"concurrency", 8}, {"default_on", false}};
        m_settings = {{"log_level", "info"}, {"inbound", inbound}, {"routing", routing}, {"tun", tun}, {"mux", mux}};
    }
    emit nodesChanged(); emit groupsChanged(); emit settingsChanged(); emit uiChanged();
}

void Store::save() {
    QJsonObject root{
        {"nodes", QJsonArray::fromVariantList(m_nodes)},
        {"groups", QJsonArray::fromVariantList(m_groups)},
        {"settings", QJsonObject::fromVariantMap(m_settings)},
        {"ui", QJsonObject::fromVariantMap(m_ui)},
        {"next_id", m_nextId},
    };
    QSaveFile f(m_file);
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(root).toJson(QJsonDocument::Indented));
        f.commit();
    }
}

int Store::addNode(const QString &nodeJson, const QString &group) {
    const auto obj = QJsonDocument::fromJson(nodeJson.toUtf8()).object();
    if (obj.isEmpty()) return -1;
    const int id = m_nextId++;
    m_nodes << QVariantMap{{"id", id}, {"group", group}, {"lat", 0}, {"node", obj.toVariantMap()}};
    emit nodesChanged();
    save();
    return id;
}

int Store::addNodes(const QStringList &nodeJsons, const QString &group) {
    int n = 0;
    for (const auto &j : nodeJsons) {
        const auto obj = QJsonDocument::fromJson(j.toUtf8()).object();
        if (obj.isEmpty()) continue;
        m_nodes << QVariantMap{{"id", m_nextId++}, {"group", group}, {"lat", 0}, {"node", obj.toVariantMap()}};
        ++n;
    }
    if (n) { emit nodesChanged(); save(); }
    return n;
}

void Store::updateNode(int id, const QVariantMap &patch) {
    for (auto &v : m_nodes) {
        auto m = v.toMap();
        if (m.value("id").toInt() != id) continue;
        for (auto it = patch.cbegin(); it != patch.cend(); ++it) m[it.key()] = it.value();
        v = m;
        emit nodesChanged();
        save();
        return;
    }
}

void Store::removeNode(int id) {
    for (int i = 0; i < m_nodes.size(); ++i)
        if (m_nodes[i].toMap().value("id").toInt() == id) { m_nodes.removeAt(i); break; }
    emit nodesChanged();
    save();
}

void Store::removeGroupNodes(const QString &group) {
    for (int i = m_nodes.size() - 1; i >= 0; --i)
        if (m_nodes[i].toMap().value("group").toString() == group) m_nodes.removeAt(i);
    emit nodesChanged();
    save();
}

QVariantMap Store::node(int id) const {
    for (const auto &v : m_nodes) if (v.toMap().value("id").toInt() == id) return v.toMap();
    return {};
}

QString Store::nodeJson(int id) const {
    const auto n = node(id).value("node").toMap();
    if (n.isEmpty()) return {};
    return QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap(n)).toJson(QJsonDocument::Compact));
}

void Store::addGroup(const QVariantMap &group) {
    for (const auto &g : m_groups) if (g.toMap().value("name") == group.value("name")) return;
    m_groups << group;
    emit groupsChanged();
    save();
}

void Store::updateGroup(const QString &name, const QVariantMap &patch) {
    for (auto &v : m_groups) {
        auto m = v.toMap();
        if (m.value("name").toString() != name) continue;
        for (auto it = patch.cbegin(); it != patch.cend(); ++it) m[it.key()] = it.value();
        v = m;
        emit groupsChanged();
        save();
        return;
    }
}

void Store::removeGroup(const QString &name) {
    removeGroupNodes(name);
    for (int i = 0; i < m_groups.size(); ++i)
        if (m_groups[i].toMap().value("name").toString() == name) { m_groups.removeAt(i); break; }
    emit groupsChanged();
    save();
}

// key may be dotted: "routing.final", "tun.enabled", "inbound.port".
// QML hands arrays and objects over as QJSValue, which QJsonObject turns into
// null; unwrap them into plain lists and maps before storing.
static QVariant plain(const QVariant &value) {
    return value.metaType() == QMetaType::fromType<QJSValue>() ? value.value<QJSValue>().toVariant() : value;
}

void Store::setSetting(const QString &key, const QVariant &v) {
    const QVariant value = plain(v);
    const auto parts = key.split('.');
    if (parts.size() == 1) {
        m_settings[key] = value;
    } else {
        auto sub = m_settings.value(parts[0]).toMap();
        sub[parts[1]] = value;
        m_settings[parts[0]] = sub;
    }
    emit settingsChanged();
    save();
}

QString Store::settingsJson() const {
    return QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap(m_settings)).toJson(QJsonDocument::Compact));
}

void Store::setUi(const QString &key, const QVariant &v) {
    const QVariant value = plain(v);
    if (m_ui.value(key) == value) return;
    m_ui[key] = value;
    emit uiChanged();
    save();
}
