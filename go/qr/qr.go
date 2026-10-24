// Package qr decodes and encodes QR codes for share links.
package qr

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	_ "image/jpeg"
	"image/png"

	"github.com/makiuchi-d/gozxing"
	multiqr "github.com/makiuchi-d/gozxing/multi/qrcode"
	"github.com/makiuchi-d/gozxing/qrcode"
)

// ErrNotFound is returned when an image holds no readable QR code.
var ErrNotFound = errors.New("qr: no code found")

// Decode returns the text of every QR code in a PNG or JPEG image.
func Decode(img []byte) ([]string, error) {
	m, _, err := image.Decode(bytes.NewReader(img))
	if err != nil {
		return nil, err
	}
	bmp, err := gozxing.NewBinaryBitmapFromImage(m)
	if err != nil {
		return nil, err
	}
	return decode(bmp)
}

// DecodeLuminance decodes a raw 8-bit luminance plane, such as the Y plane
// of a camera frame. Row r starts at y[r*rowStride]; rowStride >= width.
func DecodeLuminance(y []byte, width, height, rowStride int) ([]string, error) {
	if width <= 0 || height <= 0 || rowStride < width {
		return nil, errors.New("qr: bad luminance geometry")
	}
	if len(y) < (height-1)*rowStride+width {
		return nil, errors.New("qr: luminance plane too short")
	}
	// rowStride is the source's data width; the crop drops the padding.
	src, err := gozxing.NewPlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
	if err != nil {
		return nil, err
	}
	bmp, err := gozxing.NewBinaryBitmap(gozxing.NewHybridBinarizer(src))
	if err != nil {
		return nil, err
	}
	return decode(bmp)
}

func decode(bmp *gozxing.BinaryBitmap) ([]string, error) {
	hints := map[gozxing.DecodeHintType]interface{}{gozxing.DecodeHintType_TRY_HARDER: true}
	results, err := multiqr.NewQRCodeMultiReader().DecodeMultiple(bmp, hints)
	if err != nil || len(results) == 0 {
		// The multi reader misses some codes the single reader finds.
		r, err := qrcode.NewQRCodeReader().Decode(bmp, hints)
		if err != nil {
			return nil, ErrNotFound
		}
		results = []*gozxing.Result{r}
	}
	out := make([]string, 0, len(results))
	for _, r := range results {
		out = append(out, r.GetText())
	}
	return out, nil
}

// Encode renders text as a size x size grayscale PNG (EC level M, UTF-8,
// margin 2).
func Encode(text string, size int) ([]byte, error) {
	hints := map[gozxing.EncodeHintType]interface{}{
		gozxing.EncodeHintType_ERROR_CORRECTION: "M",
		gozxing.EncodeHintType_CHARACTER_SET:    "UTF-8",
		gozxing.EncodeHintType_MARGIN:           2,
	}
	m, err := qrcode.NewQRCodeWriter().Encode(text, gozxing.BarcodeFormat_QR_CODE, size, size, hints)
	if err != nil {
		return nil, err
	}
	img := image.NewGray(image.Rect(0, 0, m.GetWidth(), m.GetHeight()))
	for y := 0; y < m.GetHeight(); y++ {
		for x := 0; x < m.GetWidth(); x++ {
			if !m.Get(x, y) {
				img.SetGray(x, y, color.Gray{Y: 0xff})
			}
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
