//go:build windows

package main

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"unsafe"

	"golang.org/x/sys/windows"
)

// versionStrings reads what a program says about itself: its product name and description.
func versionStrings(path string) (product, description string) {
	var zero windows.Handle
	size, err := windows.GetFileVersionInfoSize(path, &zero)
	if err != nil || size == 0 {
		return "", ""
	}
	info := make([]byte, size)
	if windows.GetFileVersionInfo(path, 0, size, unsafe.Pointer(&info[0])) != nil {
		return "", ""
	}
	// the languages this program describes itself in; English US Unicode as the fallback
	langs := []string{"040904b0", "040904e4", "000004b0"}
	var tr *[2]uint16
	var trLen uint32
	if windows.VerQueryValue(unsafe.Pointer(&info[0]), `\VarFileInfo\Translation`, unsafe.Pointer(&tr), &trLen) == nil && trLen >= 4 {
		langs = append([]string{fmt.Sprintf("%04x%04x", tr[0], tr[1])}, langs...)
	}
	read := func(key string) string {
		for _, l := range langs {
			var p *uint16
			var n uint32
			if windows.VerQueryValue(unsafe.Pointer(&info[0]), `\StringFileInfo\`+l+`\`+key, unsafe.Pointer(&p), &n) == nil && n > 0 {
				return windows.UTF16PtrToString(p)
			}
		}
		return ""
	}
	return read("ProductName"), read("FileDescription")
}

// programName is how the program in front is listed; the first time a program is seen, its
// own name is read and remembered. It says whether the program is new to this PC.
func programName(app, path string) (string, bool) {
	if n, ok := programs.Name(app); ok {
		return n, false
	}
	if path == "" {
		return AppName(app), false
	}
	product, desc := versionStrings(path)
	name := FriendlyName(app, product, desc)
	if known := AppName(app); !programs.has(app) && known != baseName(app) {
		name = known // a hand-picked name ("VS Code") beats the program's own ("Visual Studio Code")
	}
	return name, programs.Learn(app, name)
}

var (
	gdi32                   = windows.NewLazySystemDLL("gdi32.dll")
	procPrivateExtractIcons = user32.NewProc("PrivateExtractIconsW")
	procGetIconInfo         = user32.NewProc("GetIconInfo")
	procDestroyIcon         = user32.NewProc("DestroyIcon")
	procGetDIBits           = gdi32.NewProc("GetDIBits")
	procGetObjectW          = gdi32.NewProc("GetObjectW")
	procCreateCompatibleDC  = gdi32.NewProc("CreateCompatibleDC")
	procDeleteDC            = gdi32.NewProc("DeleteDC")
	procDeleteObject        = gdi32.NewProc("DeleteObject")
)

type iconInfo struct {
	fIcon    int32
	xHotspot uint32
	yHotspot uint32
	hbmMask  windows.Handle
	hbmColor windows.Handle
}

type bitmap struct {
	bmType       int32
	bmWidth      int32
	bmHeight     int32
	bmWidthBytes int32
	bmPlanes     uint16
	bmBitsPixel  uint16
	bmBits       uintptr
}

type bitmapInfoHeader struct {
	size          uint32
	width, height int32
	planes, bits  uint16
	compression   uint32
	sizeImage     uint32
	xppm, yppm    int32
	clrUsed       uint32
	clrImportant  uint32
}

// iconPNG draws a program's own icon from its file at up to size pixels, as a PNG.
func iconPNG(path string, size int) ([]byte, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	var hicon windows.Handle
	var id uint32
	n, _, _ := procPrivateExtractIcons.Call(uintptr(unsafe.Pointer(p)), 0, uintptr(size), uintptr(size),
		uintptr(unsafe.Pointer(&hicon)), uintptr(unsafe.Pointer(&id)), 1, 0)
	if n == 0 || n == 0xFFFFFFFF || hicon == 0 {
		return nil, fmt.Errorf("no icon in %s", path)
	}
	defer procDestroyIcon.Call(uintptr(hicon))
	var ii iconInfo
	if r, _, _ := procGetIconInfo.Call(uintptr(hicon), uintptr(unsafe.Pointer(&ii))); r == 0 || ii.hbmColor == 0 {
		if ii.hbmMask != 0 {
			procDeleteObject.Call(uintptr(ii.hbmMask))
		}
		return nil, fmt.Errorf("monochrome icon")
	}
	defer procDeleteObject.Call(uintptr(ii.hbmColor))
	defer procDeleteObject.Call(uintptr(ii.hbmMask))
	var bm bitmap
	procGetObjectW.Call(uintptr(ii.hbmColor), unsafe.Sizeof(bm), uintptr(unsafe.Pointer(&bm)))
	w, h := int(bm.bmWidth), int(bm.bmHeight)
	if w <= 0 || h <= 0 || w > 512 || h > 512 {
		return nil, fmt.Errorf("odd icon size %dx%d", w, h)
	}
	hdc, _, _ := procCreateCompatibleDC.Call(0)
	defer procDeleteDC.Call(hdc)
	pixels := func(hbm windows.Handle) []byte {
		hdr := bitmapInfoHeader{size: 40, width: int32(w), height: -int32(h), planes: 1, bits: 32}
		buf := make([]byte, w*h*4)
		if r, _, _ := procGetDIBits.Call(hdc, uintptr(hbm), 0, uintptr(h), uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&hdr)), 0); r == 0 {
			return nil
		}
		return buf
	}
	col := pixels(ii.hbmColor)
	if col == nil {
		return nil, fmt.Errorf("could not read icon pixels")
	}
	hasAlpha := false
	for i := 3; i < len(col); i += 4 {
		if col[i] != 0 {
			hasAlpha = true
			break
		}
	}
	var mask []byte
	if !hasAlpha { // an older icon: transparency lives in the mask instead
		mask = pixels(ii.hbmMask)
	}
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			i := (y*w + x) * 4
			a := col[i+3]
			if !hasAlpha {
				a = 255
				if mask != nil && mask[i] != 0 {
					a = 0
				}
			}
			img.SetNRGBA(x, y, color.NRGBA{R: col[i+2], G: col[i+1], B: col[i], A: a})
		}
	}
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
