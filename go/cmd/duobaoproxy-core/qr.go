package main

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"duobaoproxy/qr"
)

// decodeQR prints the text of every QR code found in a PNG, one per line.
// The GUI grabs the screen and calls "DuoBaoProxyCore qr <file>".
func decodeQR(path string) int {
	img, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	texts, err := qr.Decode(img)
	if errors.Is(err, qr.ErrNotFound) {
		return 2
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	for _, t := range texts {
		fmt.Println(t)
	}
	return 0
}

// encodeQR reads one text per line from stdin and writes <dir>/<n>.png for
// each, printing the file paths in order. "DuoBaoProxyCore qr-encode <dir>"
func encodeQR(dir string) int {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 64*1024), 1024*1024)
	n := 0
	for in.Scan() {
		text := in.Text()
		if text == "" {
			continue
		}
		png, err := qr.Encode(text, 480)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		path := filepath.Join(dir, fmt.Sprintf("%d.png", n))
		if err := os.WriteFile(path, png, 0o666); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println(path)
		n++
	}
	return 0
}
