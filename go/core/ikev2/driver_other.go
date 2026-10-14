//go:build !windows && !linux && !darwin

package ikev2

import (
	"context"
	"errors"
)

type unsupportedDriver struct{}

func newDriver() driver { return unsupportedDriver{} }

var errUnsupported = errors.New("ikev2: not supported on this operating system")

func (unsupportedDriver) connect(context.Context, *Profile, func(string)) error { return errUnsupported }
func (unsupportedDriver) disconnect(context.Context, *Profile) error            { return errUnsupported }
func (unsupportedDriver) connected(context.Context, *Profile) (bool, error)     { return false, errUnsupported }
