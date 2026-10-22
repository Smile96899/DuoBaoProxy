package rpc

import (
	"context"
	"crypto/subtle"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// HeaderName is the metadata key the GUI sends the shared token in.
const HeaderName = "duobao_auth"

type authenticator struct{ token string }

func (a authenticator) check(ctx context.Context) error {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return status.Error(codes.Unauthenticated, "missing metadata")
	}
	values := md.Get(HeaderName)
	if len(values) != 1 || subtle.ConstantTimeCompare([]byte(values[0]), []byte(a.token)) != 1 {
		return status.Error(codes.Unauthenticated, "bad token")
	}
	return nil
}

func (a authenticator) unary(ctx context.Context, req any, _ *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
	if err := a.check(ctx); err != nil {
		return nil, err
	}
	return handler(ctx, req)
}

func (a authenticator) stream(srv any, ss grpc.ServerStream, _ *grpc.StreamServerInfo, handler grpc.StreamHandler) error {
	if err := a.check(ss.Context()); err != nil {
		return err
	}
	return handler(srv, ss)
}
