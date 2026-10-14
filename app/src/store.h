#pragma once
// Store keeps everything the GUI persists in one JSON file:
//   nodes    [{id, group, lat, node:{...node.Node JSON...}}]
//   groups   [{name, sub_url, sub_interval_min, updated, used, total, expire}]
//   settings {...render.Settings JSON...}
//   ui       {layout, theme, core, selected, mode, proxy_mode}
// QML reads the four properties and writes through set*/add*/remove*.

#include <QObject>
#include <QVariant>

class Store : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList nodes READ nodes NOTIFY nodesChanged)
    Q_PROPERTY(QVariantList groups READ groups NOTIFY groupsChanged)
    Q_PROPERTY(QVariantMap settings READ settings NOTIFY settingsChanged)
    Q_PROPERTY(QVariantMap ui READ ui NOTIFY uiChanged)
    Q_PROPERTY(QString dataDir READ dataDir CONSTANT)
public:
    explicit Store(QObject *parent = nullptr);

    QVariantList nodes() const { return m_nodes; }
    QVariantList groups() const { return m_groups; }
    QVariantMap settings() const { return m_settings; }
    QVariantMap ui() const { return m_ui; }
    QString dataDir() const { return m_dir; }

    Q_INVOKABLE void load();
    Q_INVOKABLE void save();

    Q_INVOKABLE int addNode(const QString &nodeJson, const QString &group);
    Q_INVOKABLE int addNodes(const QStringList &nodeJsons, const QString &group);
    Q_INVOKABLE void updateNode(int id, const QVariantMap &patch);
    Q_INVOKABLE void removeNode(int id);
    Q_INVOKABLE void removeGroupNodes(const QString &group);
    Q_INVOKABLE QVariantMap node(int id) const;
    Q_INVOKABLE QString nodeJson(int id) const;

    Q_INVOKABLE void addGroup(const QVariantMap &group);
    Q_INVOKABLE void updateGroup(const QString &name, const QVariantMap &patch);
    Q_INVOKABLE void removeGroup(const QString &name);

    Q_INVOKABLE void setSetting(const QString &key, const QVariant &value);
    Q_INVOKABLE QString settingsJson() const;
    Q_INVOKABLE void setUi(const QString &key, const QVariant &value);

signals:
    void nodesChanged();
    void groupsChanged();
    void settingsChanged();
    void uiChanged();

private:
    QString m_dir, m_file;
    QVariantList m_nodes, m_groups;
    QVariantMap m_settings, m_ui;
    int m_nextId = 1;
};
