//go:build !linux && !darwin

package mobile

import "os"

// Stubs so the package builds (and its tests run) on the desktop; a TUN fd
// is never handed over there.

func tunName(int32) (string, error) { return "", os.ErrInvalid }
func findTunFd() int32              { return -1 }
func dupFd(int) (int, error)        { return -1, os.ErrInvalid }
func closeFd(int) error             { return os.ErrInvalid }
