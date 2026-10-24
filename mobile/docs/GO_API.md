# Generated Java API of `dbcore.aar` (gomobile)

Build: `DEBUG=1 bash go/build_mobile.sh` (arm64 + x86_64 for the emulator) or `bash go/build_mobile.sh` (arm64 only) → `mobile/android/app/libs/dbcore.aar`. The AAR is a build product (gitignored); rebuild it after any change under `go/`.

Package `com.duobao.core.mobile`. Every Go `error` becomes a checked `Exception`. All calls block — never call them on the main thread.

```java
class Mobile {
  static void   setup(SetupOptions) throws Exception;       // idempotent; empty TempPath → BasePath/tmp, empty AssetPath → BasePath
  static String version();
  static String listCoresJSON();                            // [{"id","version","protocols":[...],"running"}]
  static boolean supports(String coreID, String protocol);
  static void   setLogHandler(LogHandler);
  static void   setProtector(Protector);
  static void   setLocalDNS(LocalDNS);

  static ImportResult importNodes(String text);
  static String shareLink(String nodeJSON, boolean v2raynVMess) throws Exception;
  static String normalizeNode(String nodeJSON) throws Exception;
  static SubResult fetchSubscription(String url, String userAgent, int timeoutMs) throws Exception; // non-200 is a normal result: check Status; "" UA → DuoBaoProxy/<version>; ≤0 timeout → 30 s; >16 MiB body → error

  static String prepareSettings(String settingsJSON, String proxyMode, String routeParts, boolean tun) throws Exception; // port of App.qml renderSettings(); injects platform/own_package
  static RenderResult renderConfig(String coreID, String chainJSON, String settingsJSON, int mode) throws Exception;     // mode 0 Full, 1 Test, 2 Export

  static void   start(StartOptions, Host) throws Exception; // null Host = no TUN
  static void   stop() throws Exception;
  static String status();                                   // running core id or ""
  static Traffic queryStats(String tag);                    // cumulative bytes; zero on any error

  static int    tcpPing(String address, int timeoutMs) throws Exception;
  static int    urlTest(String coreID, String config, String url, int timeoutMs) throws Exception; // config "" = running core
  static TestResult probe(String name, String coreID, String config, String address, String url, int count, int timeoutMs) throws Exception;

  static String decodeQR(byte[] image) throws Exception;    // texts joined by "\n"; no code → "图片中没有可识别的二维码"
  static String decodeQRLuminance(byte[] y, int width, int height, int rowStride) throws Exception;
  static byte[] encodeQR(String text, int size) throws Exception; // PNG; size ≤0 → 480
}
interface Host {
  int    openTun(String tunConfigJSON) throws Exception;
  void   startNetworkMonitor(NetworkListener l) throws Exception; // report the current default network before returning
  void   closeNetworkMonitor() throws Exception;
  String interfacesJSON() throws Exception;
  String findOwnerJSON(int proto, String src, int sport, String dst, int dport) throws Exception;
}
interface Protector  { boolean protect(int fd); }
interface LocalDNS   { byte[] exchange(byte[] message) throws Exception; }
interface LogHandler { void writeLog(int level, String line); } // level: sing-box scale 0 panic … 6 trace
final class NetworkListener { void updateDefaultInterface(String name, int index, boolean expensive, boolean constrained); }
```

Struct classes (public no-arg constructor, `getX()`/`setX()` per field):
- `SetupOptions`: BasePath, TempPath, AssetPath, Platform, OwnPackage (String); Debug (boolean)
- `StartOptions`: CoreID, Config, StatsTagsJSON, FrontConfig (String)
- `ImportResult`: NodesJSON, ErrorsJSON (String); Count (int)
- `RenderResult`: Config, StatsTagsJSON, FrontConfig (String)
- `SubResult`: Body, UserInfo (String); Status (int)
- `TestResult`: Ms (int); Report (String)
- `Traffic`: Uplink, Downlink (long)

JSON shapes passed to the Host (full doc comments in `go/mobile/host.go`):
- **tun config**: snake_case, lists always `[]`: `inet4_address`, `inet6_address`, `mtu`, `auto_route`, `strict_route`, `dns_mode` (effective; `hijack` with auto_route), `dns_servers`, `inet4_route_range`/`inet6_route_range` (final routes, excludes already subtracted — use these for `addRoute`), raw `inet4/6_route_address`, `inet4/6_route_exclude_address`, `include_package`, `exclude_package`, `http_proxy` (only when enabled).
- **interfaces**: `[{name, index, mtu, type: "wifi"|"cellular"|"ethernet"|"other", addresses (CIDR), dns, gateways?, metered}]`.
- **owner**: `{uid, user_name, process_path, packages}`; missing/negative uid = not found.

Known gaps: ICMP probes and the cgo DNS resolver are not protected — they bypass the VPN only because the app's own package is always excluded from it (`exclude_package`). In xray-front mode `queryStats` reads xray counters only.
