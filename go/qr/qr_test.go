package qr

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/png"
	"testing"

	"github.com/makiuchi-d/gozxing"
	"github.com/makiuchi-d/gozxing/qrcode"
)

func TestDecode(t *testing.T) {
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
	var buf bytes.Buffer
	png.Encode(&buf, img)
	texts, err := Decode(buf.Bytes())
	if err != nil || len(texts) != 1 || texts[0] != link {
		t.Fatalf("got %q %v", texts, err)
	}
}

func TestDecodeNotFound(t *testing.T) {
	img := image.NewGray(image.Rect(0, 0, 100, 100))
	var buf bytes.Buffer
	png.Encode(&buf, img)
	if _, err := Decode(buf.Bytes()); !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
	if _, err := Decode([]byte("not an image")); err == nil || errors.Is(err, ErrNotFound) {
		t.Fatalf("want a decode error, got %v", err)
	}
}

func TestEncodeRoundTrip(t *testing.T) {
	const link = "vless://3f1c2a4e-1111-2222-3333-444455556666@example.com:443?security=tls&sni=example.com#节点 一"
	b, err := Encode(link, 480)
	if err != nil {
		t.Fatal(err)
	}
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	if img.Bounds().Dx() != 480 || img.Bounds().Dy() != 480 {
		t.Fatalf("size %v", img.Bounds())
	}
	bmp, _ := gozxing.NewBinaryBitmapFromImage(img)
	r, err := qrcode.NewQRCodeReader().Decode(bmp, nil)
	if err != nil || r.GetText() != link {
		t.Fatalf("decoded %v %v", r, err)
	}
}

func TestDecodeLuminance(t *testing.T) {
	const link = "trojan://secret@example.com:443?sni=example.com#luma"
	b, err := Encode(link, 300)
	if err != nil {
		t.Fatal(err)
	}
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	// Copy into a camera-like Y plane: a code offset inside a wider frame,
	// each row padded to the stride with junk, and no padding after the
	// last row (as Android image planes often are).
	const width, height, stride, ox, oy = 400, 360, 448, 37, 23
	y := make([]byte, (height-1)*stride+width)
	for i := range y {
		y[i] = 0x55
	}
	for r := 0; r < height; r++ {
		for c := 0; c < width; c++ {
			v := byte(0xff)
			if p := image.Pt(c-ox, r-oy); p.In(img.Bounds()) {
				v = color.GrayModel.Convert(img.At(p.X, p.Y)).(color.Gray).Y
			}
			y[r*stride+c] = v
		}
	}
	texts, err := DecodeLuminance(y, width, height, stride)
	if err != nil || len(texts) != 1 || texts[0] != link {
		t.Fatalf("got %q %v", texts, err)
	}
	// The padding is not image data: reading the plane as stride == width
	// must not find the code.
	if _, err := DecodeLuminance(y, width, len(y)/width, width); err == nil {
		t.Fatal("decoded a misread plane")
	}
	if _, err := DecodeLuminance(y[:len(y)-1], width, height, stride); err == nil {
		t.Fatal("short plane accepted")
	}
	if _, err := DecodeLuminance(y, width, height, width-1); err == nil {
		t.Fatal("stride < width accepted")
	}
}
