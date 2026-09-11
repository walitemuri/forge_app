FROM python:3.12-alpine

RUN apk add --no-cache docker-cli

WORKDIR /app

COPY chaos-agent/server.py /app/server.py

EXPOSE 8090

CMD ["python3", "/app/server.py"]
