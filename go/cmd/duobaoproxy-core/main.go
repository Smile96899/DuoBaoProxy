// DuoBaoProxyCore hosts every proxy engine behind one gRPC endpoint for the GUI.
//
// Usage:
//
//	DuoBaoProxyCore run -port 19810 -token <secret> [-debug]
//	DuoBaoProxyCore version
//	DuoBaoProxyCore qr <image.png>
//	DuoBaoProxyCore qr-encode <dir>     (texts on stdin, one per line)
package main

import (
	"fmt"
	"log"
	"os"
	"runtime"

	"duobaoproxy/core"
	"duobaoproxy/core/singbox"
	"duobaoproxy/core/xray"
	"duobaoproxy/rpc"
)

// Version is set at build time with -ldflags "-X main.Version=...".
var Version = "dev"

func main() {
	core.Register(singbox.New())
	core.Register(xray.New())

	if len(os.Args) == 3 && os.Args[1] == "qr" {
		os.Exit(decodeQR(os.Args[2]))
	}
	if len(os.Args) == 3 && os.Args[1] == "qr-encode" {
		os.Exit(encodeQR(os.Args[2]))
	}
	if len(os.Args) < 2 || os.Args[1] != "run" {
		fmt.Println("DuoBao Proxy core", Version, runtime.GOOS+"/"+runtime.GOARCH)
		for _, c := range core.List() {
			fmt.Printf("  %-9s %s\n", c.ID(), c.Version())
		}
		return
	}

	lw, err := newLogFile("./core.log", 50*1024)
	if err != nil {
		log.Println("log file:", err)
	}
	logFn := func(line string) {
		if lw != nil {
			lw.WriteLine(line)
		}
	}
	rpc.Run(os.Args[2:], rpc.NewServer(logFn))
}
