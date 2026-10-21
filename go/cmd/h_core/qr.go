package main

import (
	"bufio"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"

	"github.com/makiuchi-d/gozxing"
	multiqr "github.com/makiuchi-d/gozxing/multi/qrcode"
	"github.com/makiuchi-d/gozxing/qrcode"
)

// decodeQR prints the text of every QR code found in a PNG, one per line.
// The GUI grabs the screen and calls "h_core qr <file>".
func decodeQR(path string) int {
	f, err := os.Open(path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	img, _, err := image.Decode(f)
	f.Close()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	bmp, err := gozxing.NewBinaryBitmapFromImage(img)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	hints := map[gozxing.DecodeHintType]interface{}{gozxing.DecodeHintType_TRY_HARDER: true}
	results, err := multiqr.NewQRCodeMultiReader().DecodeMultiple(bmp, hints)
	if err != nil || len(results) == 0 {
		// The multi reader misses some codes the single reader finds.
		r, err := qrcode.NewQRCodeReader().Decode(bmp, hints)
		if err != nil {
			return 2
		}
		results = []*gozxing.Result{r}
	}
	for _, r := range results {
		fmt.Println(r.GetText())
	}
	return 0
}

// encodeQR reads one text per line from stdin and writes <dir>/<n>.png for
// each, printing the file paths in order. "h_core qr-encode <dir>"
func encodeQR(dir string) int {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 64*1024), 1024*1024)
	hints := map[gozxing.EncodeHintType]interface{}{
		gozxing.EncodeHintType_ERROR_CORRECTION: "M",
		gozxing.EncodeHintType_CHARACTER_SET:    "UTF-8",
		gozxing.EncodeHintType_MARGIN:           2,
	}
	n := 0
	for in.Scan() {
		text := in.Text()
		if text == "" {
			continue
		}
		m, err := qrcode.NewQRCodeWriter().Encode(text, gozxing.BarcodeFormat_QR_CODE, 480, 480, hints)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		img := image.NewGray(image.Rect(0, 0, m.GetWidth(), m.GetHeight()))
		for y := 0; y < m.GetHeight(); y++ {
			for x := 0; x < m.GetWidth(); x++ {
				if !m.Get(x, y) {
					img.SetGray(x, y, color.Gray{Y: 0xff})
				}
			}
		}
		path := filepath.Join(dir, fmt.Sprintf("%d.png", n))
		f, err := os.Create(path)
		if err == nil {
			err = png.Encode(f, img)
			f.Close()
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println(path)
		n++
	}
	return 0
}
