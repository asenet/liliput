FROM golang:1.21-alpine AS go-builder

WORKDIR /app
COPY go.mod ./
COPY pkg/ pkg/

# Build binary with platform suffix that Grafana expects
RUN go mod tidy && \
    GOARCH=$(go env GOARCH) && \
    go build -o "gpx_liliput-datasource_linux_${GOARCH}" ./pkg

FROM node:18-alpine AS frontend-builder

WORKDIR /app
COPY package.json tsconfig.json webpack.config.js ./
COPY src/ src/

RUN npm install --ignore-scripts && npm run build
RUN cp src/plugin.json dist/
RUN mkdir -p dist/img
COPY img/ dist/img/

FROM alpine:3.19

WORKDIR /build
COPY --from=go-builder /app/gpx_liliput-datasource_linux_* ./
COPY --from=frontend-builder /app/dist/ ./

CMD ["sh", "-c", "cp -r /build/* /plugin/ && chmod +x /plugin/gpx_* && echo 'Plugin ready:' && ls -la /plugin/"]
