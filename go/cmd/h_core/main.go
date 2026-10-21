// h_core hosts every proxy engine behind one gRPC endpoint for the GUI.
//
// Usage:
//
//	h_core run -port 19810 -token <secret> [-debug]
//	h_core version
//	h_core qr <image.png>
//	h_core qr-encode <dir>     (texts on stdin, one per line)
package main

import (
	"fmt"
	"log"
	"os"
	"runtime"

	"hproxy/core"
	"hproxy/core/ikev2"
	"hproxy/core/singbox"
	"hproxy/core/xray"
	"hproxy/rpc"
)

// Version is set at build time with -ldflags "-X main.Version=...".
var Version = "dev"

func main() {
	core.Register(singbox.New())
	core.Register(xray.New())
	core.Register(ikev2.New())

	if len(os.Args) == 3 && os.Args[1] == "qr" {
		os.Exit(decodeQR(os.Args[2]))
	}
	if len(os.Args) == 3 && os.Args[1] == "qr-encode" {
		os.Exit(encodeQR(os.Args[2]))
	}
	if len(os.Args) < 2 || os.Args[1] != "run" {
		fmt.Println("h.proxy core", Version, runtime.GOOS+"/"+runtime.GOARCH)
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
