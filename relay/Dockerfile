FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /relay .

# Railway mounts volumes owned by root, so the default (root) user is kept to write /data.
FROM gcr.io/distroless/static-debian12
COPY --from=build /relay /relay
ENV DATA_DIR=/data
ENTRYPOINT ["/relay"]
