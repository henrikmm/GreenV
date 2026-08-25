FROM alpine:3.22

RUN apk add --no-cache bash curl ffmpeg jq

COPY smoke.sh /usr/local/bin/greenv-capture-smoke
RUN chmod 0755 /usr/local/bin/greenv-capture-smoke

ENTRYPOINT ["/usr/local/bin/greenv-capture-smoke"]
