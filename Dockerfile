FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    curl -fLo /tmp/sbt.tgz "https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz" && \
    tar xzf /tmp/sbt.tgz -C /usr/local && \
    rm /tmp/sbt.tgz && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV PATH="/usr/local/sbt/bin:${PATH}"

WORKDIR /build

COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
RUN sbt update

COPY project/ project/
COPY src/ src/
RUN sbt assembly

# -----------------------------------------------------------
# Pre-build binary index (vectors.bin, order.bin, medians.bin, labels.bin)
FROM eclipse-temurin:21-jre AS indexer

WORKDIR /work
COPY --from=builder /build/target/scala-3.3.7/app.jar app.jar
COPY resources/references.json.gz data/references.json.gz

ENV DATA_DIR=/work/data
ENV INDEX_DIR=/work/index
ENV REFERENCES_FILE=references.json.gz
ENV REFERENCES_EXPECTED_COUNT=3100000

RUN mkdir -p /work/index && \
    java -XX:+UseG1GC -Xmx512m -cp app.jar rinha.tools.IndexBuilder

# -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/scala-3.3.7/app.jar app.jar
COPY --from=indexer /work/index/ index/
COPY resources/normalization.json  data/normalization.json
COPY resources/mcc_risk.json       data/mcc_risk.json

ENV DATA_DIR=/app/data
ENV INDEX_DIR=/app/index
ENV NORMALIZATION_FILE=normalization.json
ENV MCC_RISK_FILE=mcc_risk.json
ENV HTTP_PORT=8080
ENV HTTP_HOST=0.0.0.0

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseSerialGC", \
    "-Xmx96m", \
    "-XX:MaxMetaspaceSize=48m", \
    "-XX:MaxDirectMemorySize=16m", \
    "-XX:ReservedCodeCacheSize=16m", \
    "-XX:CICompilerCount=2", \
    "-Xss256k", \
    "-jar", "app.jar"]
