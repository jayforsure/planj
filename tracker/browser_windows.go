//go:build windows

package main

import (
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The browser's address bar is read through UI Automation, the same interface screen
// readers use. Only the site survives (SiteFromAddress); the full address is dropped at once.

var (
	ole32            = windows.NewLazySystemDLL("ole32.dll")
	oleaut32         = windows.NewLazySystemDLL("oleaut32.dll")
	procCoCreate     = ole32.NewProc("CoCreateInstance")
	procVariantClear = oleaut32.NewProc("VariantClear")

	clsidCUIAutomation = windows.GUID{Data1: 0xff48dba4, Data2: 0x60ef, Data3: 0x4201, Data4: [8]byte{0xaa, 0x87, 0x54, 0x10, 0x3e, 0xef, 0x59, 0x4e}}
	iidIUIAutomation   = windows.GUID{Data1: 0x30cbe57d, Data2: 0xd9d0, Data3: 0x452a, Data4: [8]byte{0xab, 0x13, 0x7a, 0xc5, 0xac, 0x48, 0x25, 0xee}}
)

const (
	uiaControlTypeProperty = 30003
	uiaValueValueProperty  = 30045
	uiaEditControlType     = 50004
	treeScopeDescendants   = 4
	vtI4                   = 3
	vtBSTR                 = 8
	clsctxInprocServer     = 1
)

type variant struct {
	vt  uint16
	_   [3]uint16
	val [16]byte // the union; a BSTR pointer or an int32 sits at the start
}

// comCall calls method index i of a COM object's vtable.
func comCall(obj unsafe.Pointer, i int, args ...uintptr) uintptr {
	vtbl := *(*unsafe.Pointer)(obj)
	fn := *(*uintptr)(unsafe.Add(vtbl, i*int(unsafe.Sizeof(uintptr(0)))))
	r, _, _ := syscall.SyscallN(fn, append([]uintptr{uintptr(obj)}, args...)...)
	return r
}

func comRelease(obj unsafe.Pointer) {
	if obj != nil {
		comCall(obj, 2)
	}
}

// siteReader holds one UI Automation object and the Edit-control condition, reused every poll.
// It must be used from the thread that called CoInitializeEx.
type siteReader struct {
	uia, editCond unsafe.Pointer
	lastHwnd      windows.HWND
	lastTitle     string
	lastSite      string
}

func newSiteReader() *siteReader {
	if err := windows.CoInitializeEx(0, windows.COINIT_APARTMENTTHREADED); err != nil {
		return nil
	}
	var uia unsafe.Pointer
	if r, _, _ := procCoCreate.Call(uintptr(unsafe.Pointer(&clsidCUIAutomation)), 0, clsctxInprocServer,
		uintptr(unsafe.Pointer(&iidIUIAutomation)), uintptr(unsafe.Pointer(&uia))); r != 0 || uia == nil {
		return nil
	}
	v := variant{vt: vtI4}
	*(*int32)(unsafe.Pointer(&v.val)) = uiaEditControlType
	var cond unsafe.Pointer
	// IUIAutomation::CreatePropertyCondition(id, VARIANT, **cond); a 16-byte VARIANT goes by reference.
	if comCall(uia, 23, uiaControlTypeProperty, uintptr(unsafe.Pointer(&v)), uintptr(unsafe.Pointer(&cond))) != 0 || cond == nil {
		comRelease(uia)
		return nil
	}
	return &siteReader{uia: uia, editCond: cond}
}

// Site returns the site open in a browser window, reading the address bar only when the
// window or its title changed since last time (a new tab or page), so polls stay cheap.
func (sr *siteReader) Site(hwnd windows.HWND, title string) string {
	if sr == nil || hwnd == 0 || IsPrivateWindow(title) {
		return ""
	}
	if hwnd == sr.lastHwnd && title == sr.lastTitle {
		return sr.lastSite
	}
	site := SiteFromAddress(sr.address(hwnd))
	sr.lastHwnd, sr.lastTitle, sr.lastSite = hwnd, title, site
	return site
}

// address reads the first text box in the window, which in Edge, Chrome and Firefox is the
// address bar: the toolbar comes before the page in the automation tree.
func (sr *siteReader) address(hwnd windows.HWND) string {
	var el unsafe.Pointer
	if comCall(sr.uia, 6, uintptr(hwnd), uintptr(unsafe.Pointer(&el))) != 0 || el == nil { // ElementFromHandle
		return ""
	}
	defer comRelease(el)
	var edit unsafe.Pointer
	if comCall(el, 5, treeScopeDescendants, uintptr(sr.editCond), uintptr(unsafe.Pointer(&edit))) != 0 || edit == nil { // FindFirst
		return ""
	}
	defer comRelease(edit)
	var v variant
	if comCall(edit, 10, uiaValueValueProperty, uintptr(unsafe.Pointer(&v))) != 0 { // GetCurrentPropertyValue
		return ""
	}
	defer procVariantClear.Call(uintptr(unsafe.Pointer(&v)))
	bstr := *(*unsafe.Pointer)(unsafe.Pointer(&v.val))
	if v.vt != vtBSTR || bstr == nil {
		return ""
	}
	n := *(*uint32)(unsafe.Add(bstr, -4)) / 2 // BSTR length prefix, in bytes
	return windows.UTF16ToString(unsafe.Slice((*uint16)(bstr), n))
}
