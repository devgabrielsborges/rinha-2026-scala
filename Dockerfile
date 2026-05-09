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
# Pre-build IVF binary index (centroids, cluster offsets, permutation)
FROM eclipse-temurin:21-jre AS indexer

WORKDIR /work
COPY --from=builder /build/target/scala-3.3.7/app.jar app.jar
COPY resources/references.json.gz data/references.json.gz

ENV DATA_DIR=/work/data
ENV INDEX_DIR=/work/index
ENV REFERENCES_FILE=references.json.gz
ENV REFERENCES_EXPECTED_COUNT=3100000

RUN mkdir -p /work/index && \
    java --add-opens java.base/sun.misc=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    -XX:+UseG1GC -Xmx768m -cp app.jar rinha.tools.IndexBuilder

# -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/scala-3.3.7/app.jar app.jar
COPY --from=indexer /work/index/ index/
COPY resources/normalization.json  data/normalization.json
COPY resources/mcc_risk.json       data/mcc_risk.json
COPY entrypoint.sh entrypoint.sh

ENV DATA_DIR=/app/data
ENV INDEX_DIR=/app/index
ENV NORMALIZATION_FILE=normalization.json
ENV MCC_RISK_FILE=mcc_risk.json
ENV HTTP_PORT=9999
ENV HTTP_HOST=0.0.0.0

EXPOSE 9999

ENTRYPOINT ["/app/entrypoint.sh"]
