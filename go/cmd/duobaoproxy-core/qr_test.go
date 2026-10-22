package main

import (
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/makiuchi-d/gozxing"
	"github.com/makiuchi-d/gozxing/qrcode"
)

func TestDecodeQR(t *testing.T) {
	const link = "ss://YWVzLTI1Ni1nY206cGFzcw@1.2.3.4:8388#test"
	m, err := qrcode.NewQRCodeWriter().Encode(link, gozxing.BarcodeFormat_QR_CODE, 200, 200, nil)
	if err != nil {
		t.Fatal(err)
	}
	// Place the code on a larger screen-like canvas.
	img := image.NewGray(image.Rect(0, 0, 800, 500))
	for i := range img.Pix {
		img.Pix[i] = 0xff
	}
	for y := 0; y < 200; y++ {
		for x := 0; x < 200; x++ {
			if m.Get(x, y) {
				img.SetGray(300+x, 150+y, color.Gray{})
			}
		}
	}
	path := filepath.Join(t.TempDir(), "screen.png")
	f, _ := os.Create(path)
	png.Encode(f, img)
	f.Close()

	r, w, _ := os.Pipe()
	stdout := os.Stdout
	os.Stdout = w
	code := decodeQR(path)
	w.Close()
	os.Stdout = stdout
	buf := make([]byte, 256)
	n, _ := r.Read(buf)
	if code != 0 || string(buf[:n]) != link+"\n" {
		t.Fatalf("code %d, got %q", code, buf[:n])
	}
}

func TestEncodeQRRoundTrip(t *testing.T) {
	const link = "vless://3f1c2a4e-1111-2222-3333-444455556666@example.com:443?security=tls&sni=example.com#节点 一"
	dir := t.TempDir()
	in, w, _ := os.Pipe()
	stdin := os.Stdin
	os.Stdin = in
	w.WriteString(link + "\n")
	w.Close()
	devnull, _ := os.Open(os.DevNull)
	stdout := os.Stdout
	os.Stdout = devnull
	code := encodeQR(dir)
	os.Stdin, os.Stdout = stdin, stdout
	if code != 0 {
		t.Fatalf("encode exit %d", code)
	}
	f, err := os.Open(filepath.Join(dir, "0.png"))
	if err != nil {
		t.Fatal(err)
	}
	img, _, err := image.Decode(f)
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	bmp, _ := gozxing.NewBinaryBitmapFromImage(img)
	r, err := qrcode.NewQRCodeReader().Decode(bmp, nil)
	if err != nil || r.GetText() != link {
		t.Fatalf("decoded %v %v", r, err)
	}
}
