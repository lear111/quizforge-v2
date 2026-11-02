FROM python:3.13-alpine
RUN apk add --no-cache openssl && adduser -D -u 10001 fixture
WORKDIR /fixture
COPY server.py .
USER fixture
EXPOSE 8443
CMD ["sh", "-c", "openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/key.pem -out /tmp/cert.pem -days 1 -subj /CN=audio.qf.test -addext subjectAltName=DNS:audio.qf.test >/dev/null 2>&1 && python -u server.py"]
